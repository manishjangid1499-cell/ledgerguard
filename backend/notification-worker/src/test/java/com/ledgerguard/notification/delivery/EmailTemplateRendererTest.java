package com.ledgerguard.notification.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmailTemplateRenderer Unit Tests")
class EmailTemplateRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer(objectMapper);

    @Test
    @DisplayName("Renders transfer sender notification with masked wallet and rupee formatting")
    void rendersTransferSenderNotification() {
        ObjectNode node = objectMapper.createObjectNode();
        UUID transferId = UUID.randomUUID();
        node.put("transferId", transferId.toString());
        node.put("amountMinor", "2500");
        node.put("currency", "INR");
        node.put("destinationLedgerAccountId", "12345678-abcd-ef01-2345-6789abcdef01");

        String body = renderer.renderBody(EmailTemplateRenderer.TEMPLATE_TRANSFER_SENDER, node.toString());

        assertThat(body).contains("₹25.00");
        assertThat(body).contains("12345678...ef01");
        assertThat(body).contains("Transfer ID: " + transferId);
    }

    @Test
    @DisplayName("Renders transfer receiver notification with rupee formatting")
    void rendersTransferReceiverNotification() {
        ObjectNode node = objectMapper.createObjectNode();
        UUID transferId = UUID.randomUUID();
        node.put("transferId", transferId.toString());
        node.put("amountMinor", "15075");
        node.put("currency", "INR");
        node.put("sourceEmail", "alice@example.com");

        String body = renderer.renderBody(EmailTemplateRenderer.TEMPLATE_TRANSFER_RECEIVER, node.toString());

        assertThat(body).contains("₹150.75");
        assertThat(body).contains("Transfer ID: " + transferId);
    }

    @Test
    @DisplayName("Renders customer payment confirmation")
    void rendersCustomerPaymentNotification() {
        ObjectNode node = objectMapper.createObjectNode();
        UUID paymentId = UUID.randomUUID();
        node.put("paymentId", paymentId.toString());
        node.put("grossAmountMinor", "100000");
        node.put("currency", "INR");

        String body = renderer.renderBody(EmailTemplateRenderer.TEMPLATE_PAYMENT_CUSTOMER, node.toString());

        assertThat(body).contains("₹1000.00");
        assertThat(body).contains("Payment ID: " + paymentId);
    }

    @Test
    @DisplayName("Renders merchant payment received with fee and net breakdown")
    void rendersMerchantPaymentNotification() {
        ObjectNode node = objectMapper.createObjectNode();
        UUID paymentId = UUID.randomUUID();
        node.put("paymentId", paymentId.toString());
        node.put("grossAmountMinor", "5000");
        node.put("feeAmountMinor", "100");
        node.put("merchantNetAmountMinor", "4900");
        node.put("currency", "INR");

        String body = renderer.renderBody(EmailTemplateRenderer.TEMPLATE_PAYMENT_MERCHANT, node.toString());

        assertThat(body).contains("Gross: ₹50.00");
        assertThat(body).contains("Platform fee: ₹1.00");
        assertThat(body).contains("Net received: ₹49.00");
        assertThat(body).contains("Payment ID: " + paymentId);
    }

    @Test
    @DisplayName("Renders customer refund confirmation")
    void rendersCustomerRefundNotification() {
        ObjectNode node = objectMapper.createObjectNode();
        UUID refundId = UUID.randomUUID();
        node.put("refundId", refundId.toString());
        node.put("paymentId", UUID.randomUUID().toString());
        node.put("refundAmountMinor", "2000");
        node.put("currency", "INR");

        String body = renderer.renderBody(EmailTemplateRenderer.TEMPLATE_REFUND_CUSTOMER, node.toString());

        assertThat(body).contains("₹20.00");
        assertThat(body).contains("Refund ID: " + refundId);
    }

    @Test
    @DisplayName("Renders merchant refund debited notification")
    void rendersMerchantRefundNotification() {
        ObjectNode node = objectMapper.createObjectNode();
        UUID refundId = UUID.randomUUID();
        node.put("refundId", refundId.toString());
        node.put("refundAmountMinor", "2000");
        node.put("currency", "INR");

        String body = renderer.renderBody(EmailTemplateRenderer.TEMPLATE_REFUND_MERCHANT, node.toString());

        assertThat(body).contains("Your refund of ₹20.00 was completed");
        assertThat(body).contains("Refund ID: " + refundId);
    }
}
