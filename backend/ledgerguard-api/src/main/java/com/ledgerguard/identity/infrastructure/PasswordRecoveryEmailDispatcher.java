package com.ledgerguard.identity.infrastructure;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dispatches password recovery and password update notification emails.
 * <p>
 * Key security & architectural properties:
 * 1. Executes strictly outside database transactions (afterCommit synchronization).
 * 2. Uses a bounded in-memory work queue (capacity 1000) to decouple HTTP threads from SMTP latency.
 *    DURABILITY NOTE: The in-memory email work queue is NOT durable. If the application process
 *    restarts or terminates before the queue drains, in-flight emails are discarded.
 *    Queue-full and delivery failures fail safely and preserve the uniform generic forgot-password response.
 * 3. Never publishes raw reset tokens or reset URLs to Kafka, outbox events, or audit logs.
 * 4. Failure logs are strictly sanitized: exception messages, email bodies, reset URLs,
 *    and raw tokens are NEVER written to logs.
 * 5. Bounded retries (up to 3 attempts with exponential backoff) for transient errors.
 */
@Component
public class PasswordRecoveryEmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryEmailDispatcher.class);
    private static final int QUEUE_CAPACITY = 1000;
    private static final int MAX_ATTEMPTS = 3;

    public record EmailTask(String to, String subject, String body) {}

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PasswordRecoveryProperties properties;
    private final BlockingQueue<EmailTask> taskQueue;
    private final ExecutorService workerExecutor;
    private volatile boolean running = true;

    public PasswordRecoveryEmailDispatcher(ObjectProvider<JavaMailSender> mailSenderProvider,
                                           PasswordRecoveryProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.taskQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.workerExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "password-recovery-email-worker-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
        this.workerExecutor.submit(this::processQueue);
    }

    /**
     * Enqueues an email task. Does not block the HTTP transaction.
     * Returns false if the queue is full.
     */
    public boolean enqueueEmail(String to, String subject, String body) {
        if (!running) {
            log.warn("Password recovery email dispatcher is stopping; dropping email");
            return false;
        }
        EmailTask task = new EmailTask(to, subject, body);
        boolean offered = taskQueue.offer(task);
        if (!offered) {
            log.error("Password recovery email queue is full ({}/{}). Dropped email task.",
                    taskQueue.size(), QUEUE_CAPACITY);
        }
        return offered;
    }

    private void processQueue() {
        while (running || !taskQueue.isEmpty()) {
            try {
                EmailTask task = taskQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task != null) {
                    dispatchWithRetry(task);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in password recovery email worker: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void dispatchWithRetry(EmailTask task) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("JavaMailSender bean is not configured in current environment. Dropping email.");
            return;
        }

        int attempt = 0;
        long backoffMs = 500;
        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            try {
                MimeMessage mimeMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());

                String from = properties.getFromEmail();
                String fromName = properties.getFromName();
                if (fromName != null && !fromName.isBlank()) {
                    helper.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
                } else {
                    helper.setFrom(from);
                }

                helper.setTo(task.to());
                helper.setSubject(task.subject());
                helper.setText(task.body(), false);

                mailSender.send(mimeMessage);
                log.info("Dispatched password recovery email to {}", maskEmail(task.to()));
                return;
            } catch (Exception e) {
                log.warn("Failed to dispatch email (attempt {}/{}) [cause={}]", attempt, MAX_ATTEMPTS, e.getClass().getSimpleName());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Email retry sleep interrupted");
                        return;
                    }
                } else {
                    log.error("Exhausted all {} attempts sending email. Message dropped.", MAX_ATTEMPTS);
                }
            }
        }
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "unknown";
        }
        int atIdx = email.indexOf('@');
        if (atIdx <= 1) {
            return "***" + (atIdx >= 0 ? email.substring(atIdx) : "");
        }
        return email.charAt(0) + "***" + email.substring(atIdx);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
