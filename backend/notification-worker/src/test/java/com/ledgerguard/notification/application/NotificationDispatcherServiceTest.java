package com.ledgerguard.notification.application;

import com.ledgerguard.notification.delivery.EmailDeliveryException;
import com.ledgerguard.notification.delivery.EmailMessage;
import com.ledgerguard.notification.delivery.EmailSender;
import com.ledgerguard.notification.delivery.EmailTemplateRenderer;
import com.ledgerguard.notification.domain.NotificationDelivery;
import com.ledgerguard.notification.domain.NotificationDeliveryStatus;
import com.ledgerguard.notification.infrastructure.NotificationDeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDispatcherService Unit Tests")
class NotificationDispatcherServiceTest {

    @Mock
    private NotificationDeliveryRepository deliveryRepository;

    @Mock
    private EmailSender emailSender;

    @Mock
    private ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;

    private EmailTemplateRenderer templateRenderer;
    private NotificationDispatcherService dispatcherService;

    @BeforeEach
    void setUp() {
        templateRenderer = new EmailTemplateRenderer(new ObjectMapper());
        when(meterRegistryProvider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());

        dispatcherService = new NotificationDispatcherService(
                deliveryRepository,
                emailSender,
                templateRenderer,
                meterRegistryProvider,
                true,  // emailEnabled
                10,    // batchSize
                60,    // leaseDurationSeconds
                3,     // maxAttempts
                5,     // initialRetryDelaySeconds
                60,    // maxRetryDelaySeconds
                "no-reply@ledgerguard.local",
                "LedgerGuard"
        );
        lenient().when(deliveryRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private NotificationDelivery createDeliveryWithStatus(String email, int attempts, String status) {
        Instant now = Instant.now();
        return new NotificationDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                "TRANSFER",
                UUID.randomUUID(),
                UUID.randomUUID(),
                email,
                "EMAIL",
                EmailTemplateRenderer.TEMPLATE_TRANSFER_SENDER,
                "Transfer Complete",
                "{\"transferId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":\"2500\",\"currency\":\"INR\",\"destinationLedgerAccountId\":\"" + UUID.randomUUID() + "\"}",
                status,
                attempts,
                now,
                null,
                null,
                null,
                null,
                now,
                now
        );
    }

    private NotificationDelivery createPendingDelivery(String email, int attempts) {
        return createDeliveryWithStatus(email, attempts, NotificationDeliveryStatus.PENDING.name());
    }

    @Test
    @DisplayName("Dispatches pending delivery successfully and marks status as SENT")
    void dispatchesSuccessfully() {
        NotificationDelivery delivery = createPendingDelivery("customer@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        dispatcherService.pollAndDispatch();

        verify(emailSender, times(1)).send(any(EmailMessage.class));
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT.name());
        assertThat(delivery.getSentAt()).isNotNull();
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("Handles transient SMTP error by transitioning to RETRY_PENDING with backoff")
    void handlesTransientSmtpErrorWithRetryPending() {
        NotificationDelivery delivery = createPendingDelivery("customer@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("Connection timeout to SMTP server", true))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        verify(emailSender, times(1)).send(any(EmailMessage.class));
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRY_PENDING.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(delivery.getLastError()).contains("Connection timeout");
    }

    @Test
    @DisplayName("Exceeding max attempts transitions to FAILED")
    void exceedingMaxAttemptsTransitionsToFailed() {
        NotificationDelivery delivery = createPendingDelivery("customer@example.com", 2); // already 2 attempts, max is 3
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("SMTP 421 Service unavailable", true))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        assertThat(delivery.getLastError()).contains("SMTP 421 Service unavailable");
    }

    @Test
    @DisplayName("Non-transient error immediately transitions to FAILED without retries")
    void nonTransientErrorTransitionsImmediatelyToFailed() {
        NotificationDelivery delivery = createPendingDelivery("customer@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("550 5.1.1 User unknown", false))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED.name());
        assertThat(delivery.getLastError()).contains("550 5.1.1 User unknown");
    }

    @Test
    @DisplayName("SMTP 250 / success transitions delivery to SENT")
    void smtp250SuccessTransitionsToSent() {
        NotificationDelivery delivery = createPendingDelivery("user@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        dispatcherService.pollAndDispatch();

        verify(emailSender, times(1)).send(any(EmailMessage.class));
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT.name());
        assertThat(delivery.getSentAt()).isNotNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("SMTP 421 transitions to RETRY_PENDING with backoff")
    void smtp421TransitionsToRetryPending() {
        NotificationDelivery delivery = createPendingDelivery("user@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("421 4.7.0 Service unavailable", true, 421, "TRANSIENT_SMTP_4XX"))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRY_PENDING.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNotNull();
        assertThat(delivery.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    @DisplayName("SMTP 450 transitions to RETRY_PENDING with backoff")
    void smtp450TransitionsToRetryPending() {
        NotificationDelivery delivery = createPendingDelivery("user@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("450 Mailbox busy", true, 450, "TRANSIENT_SMTP_4XX"))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRY_PENDING.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("SMTP 550 transitions to FAILED immediately without retry time")
    void smtp550TransitionsToFailedImmediately() {
        NotificationDelivery delivery = createPendingDelivery("bad@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("550 5.1.1 User unknown", false, 550, "PERMANENT_SMTP_5XX"))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("SMTP 554 transitions to FAILED immediately without retry time")
    void smtp554TransitionsToFailedImmediately() {
        NotificationDelivery delivery = createPendingDelivery("bad@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("554 Transaction failed", false, 554, "PERMANENT_SMTP_5XX"))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("SMTP 535 / authentication failure transitions to FAILED immediately")
    void smtp535AuthFailureTransitionsToFailedImmediately() {
        NotificationDelivery delivery = createPendingDelivery("user@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("535 Authentication credentials invalid", false, 535, "AUTHENTICATION_FAILURE"))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Connection timeout transitions to RETRY_PENDING")
    void connectionTimeoutTransitionsToRetryPending() {
        NotificationDelivery delivery = createPendingDelivery("user@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("Connect timed out to smtp.host", true, null, "NETWORK_CONNECTIVITY"))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRY_PENDING.name());
        assertThat(delivery.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("Successful retry still becomes SENT")
    void successfulRetryStillBecomesSent() {
        // Delivery that had previously failed once and is now on attempt 1
        NotificationDelivery delivery = createDeliveryWithStatus("user@example.com", 1, NotificationDeliveryStatus.RETRY_PENDING.name());
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        dispatcherService.pollAndDispatch();

        verify(emailSender, times(1)).send(any(EmailMessage.class));
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT.name());
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getSentAt()).isNotNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Permanent failures are not scheduled with a next retry time")
    void permanentFailuresAreNotScheduledWithNextRetryTime() {
        NotificationDelivery delivery = createPendingDelivery("bad@example.com", 0);
        when(deliveryRepository.claimDueDeliveries(any(), anyInt())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        doThrow(new EmailDeliveryException("Permanent rejection", false))
                .when(emailSender).send(any(EmailMessage.class));

        dispatcherService.pollAndDispatch();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED.name());
        assertThat(delivery.getNextAttemptAt()).isNull();
    }
}

