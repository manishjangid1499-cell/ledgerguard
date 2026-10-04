package com.ledgerguard.identity.infrastructure;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("PasswordRecoveryEmailDispatcher Tests")
class PasswordRecoveryEmailDispatcherTest {

    @Test
    @DisplayName("Successfully delivers email and interacts with JavaMailSender")
    void deliversEmailSuccessfully() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);

        PasswordRecoveryProperties props = new PasswordRecoveryProperties();
        props.setFromEmail("security@ledgerguard.com");
        props.setFromName("LedgerGuard Security");

        PasswordRecoveryEmailDispatcher dispatcher = new PasswordRecoveryEmailDispatcher(provider, props);
        try {
            boolean enqueued = dispatcher.enqueueEmail("customer@example.com", "Test Subject", "Test Body");
            assertThat(enqueued).isTrue();

            boolean delivered = latch.await(5, TimeUnit.SECONDS);
            assertThat(delivered).isTrue();
            verify(mailSender, times(1)).send(any(MimeMessage.class));
        } finally {
            dispatcher.shutdown();
        }
    }

    @Test
    @DisplayName("Retries up to max attempts on transient mail exception and safely exhausts attempts without throwing")
    void retriesOnFailureAndExhaustsSafely() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);
        doAnswer(invocation -> {
            callCount.incrementAndGet();
            latch.countDown();
            throw new RuntimeException("Simulated SMTP Connection Failure");
        }).when(mailSender).send(any(MimeMessage.class));

        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);

        PasswordRecoveryProperties props = new PasswordRecoveryProperties();
        props.setFromEmail("security@ledgerguard.com");

        PasswordRecoveryEmailDispatcher dispatcher = new PasswordRecoveryEmailDispatcher(provider, props);
        try {
            boolean enqueued = dispatcher.enqueueEmail("failed@example.com", "Failed Subject", "Failed Body");
            assertThat(enqueued).isTrue();

            boolean completedRetries = latch.await(10, TimeUnit.SECONDS);
            assertThat(completedRetries).isTrue();
            assertThat(callCount.get()).isEqualTo(3);
        } finally {
            dispatcher.shutdown();
        }
    }
}
