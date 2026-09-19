package com.ledgerguard.notification.delivery;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Component
public class EmailTemplateRenderer {

    public static final String TEMPLATE_TRANSFER_SENDER = "TRANSFER_COMPLETED_SENDER";
    public static final String TEMPLATE_TRANSFER_RECEIVER = "TRANSFER_COMPLETED_RECEIVER";
    public static final String TEMPLATE_PAYMENT_CUSTOMER = "PAYMENT_SUCCEEDED_CUSTOMER";
    public static final String TEMPLATE_PAYMENT_MERCHANT = "PAYMENT_SUCCEEDED_MERCHANT";
    public static final String TEMPLATE_REFUND_CUSTOMER = "REFUND_COMPLETED_CUSTOMER";
    public static final String TEMPLATE_REFUND_MERCHANT = "REFUND_COMPLETED_MERCHANT";

    private final ObjectMapper objectMapper;

    public EmailTemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Renders plain-text email body for the given template key and JSON payload.
     */
    public String renderBody(String templateKey, String payloadJson) {
        try {
            JsonNode data = objectMapper.readTree(payloadJson);

            return switch (templateKey) {
                case TEMPLATE_TRANSFER_SENDER -> renderTransferSender(data);
                case TEMPLATE_TRANSFER_RECEIVER -> renderTransferReceiver(data);
                case TEMPLATE_PAYMENT_CUSTOMER -> renderPaymentCustomer(data);
                case TEMPLATE_PAYMENT_MERCHANT -> renderPaymentMerchant(data);
                case TEMPLATE_REFUND_CUSTOMER -> renderRefundCustomer(data);
                case TEMPLATE_REFUND_MERCHANT -> renderRefundMerchant(data);
                default -> throw new IllegalArgumentException("Unknown email template: " + templateKey);
            };
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render email body for template " + templateKey, e);
        }
    }

    private String renderTransferSender(JsonNode data) {
        String amount = formatRupees(data.path("amountMinor").asText());
        String transferId = data.path("transferId").asText();
        String destWallet = maskWalletId(data.path("destinationLedgerAccountId").asText());

        return String.format(
                "Your transfer of %s was completed successfully.\n\n" +
                "Transfer ID: %s\n" +
                "Recipient wallet: %s\n\n" +
                "Thank you for using LedgerGuard.",
                amount, transferId, destWallet
        );
    }

    private String renderTransferReceiver(JsonNode data) {
        String amount = formatRupees(data.path("amountMinor").asText());
        String transferId = data.path("transferId").asText();

        return String.format(
                "You received %s in your LedgerGuard wallet.\n\n" +
                "Transfer ID: %s\n\n" +
                "Thank you for using LedgerGuard.",
                amount, transferId
        );
    }

    private String renderPaymentCustomer(JsonNode data) {
        String grossAmount = formatRupees(data.path("grossAmountMinor").asText());
        String paymentId = data.path("paymentId").asText();

        return String.format(
                "Your payment of %s was completed successfully.\n\n" +
                "Payment ID: %s\n" +
                "Amount paid: %s\n\n" +
                "Thank you for using LedgerGuard.",
                grossAmount, paymentId, grossAmount
        );
    }

    private String renderPaymentMerchant(JsonNode data) {
        String grossAmount = formatRupees(data.path("grossAmountMinor").asText());
        String feeAmount = formatRupees(data.path("feeAmountMinor").asText());
        String netAmount = formatRupees(data.path("merchantNetAmountMinor").asText());
        String paymentId = data.path("paymentId").asText();

        return String.format(
                "A customer payment was completed.\n\n" +
                "Gross: %s\n" +
                "Platform fee: %s\n" +
                "Net received: %s\n\n" +
                "Payment ID: %s\n\n" +
                "Thank you for using LedgerGuard.",
                grossAmount, feeAmount, netAmount, paymentId
        );
    }

    private String renderRefundCustomer(JsonNode data) {
        String refundAmount = formatRupees(data.path("refundAmountMinor").asText());
        String paymentId = data.path("paymentId").asText();
        String refundId = data.path("refundId").asText();

        return String.format(
                "A refund of %s has been completed.\n\n" +
                "Payment ID: %s\n" +
                "Refund ID: %s\n\n" +
                "Thank you for using LedgerGuard.",
                refundAmount, paymentId, refundId
        );
    }

    private String renderRefundMerchant(JsonNode data) {
        String refundAmount = formatRupees(data.path("refundAmountMinor").asText());
        String refundId = data.path("refundId").asText();

        return String.format(
                "Your refund of %s was completed successfully.\n\n" +
                "Refund ID: %s\n\n" +
                "Thank you for using LedgerGuard.",
                refundAmount, refundId
        );
    }

    public static String formatRupees(String minorUnitsStr) {
        if (minorUnitsStr == null || minorUnitsStr.isBlank()) {
            return "₹0.00";
        }
        BigDecimal minor = new BigDecimal(minorUnitsStr);
        BigDecimal rupees = minor.movePointLeft(2);
        return "₹" + rupees.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static String maskWalletId(String walletId) {
        if (walletId == null || walletId.isBlank()) {
            return "";
        }
        if (walletId.length() <= 12) {
            return walletId;
        }
        return walletId.substring(0, 8) + "..." + walletId.substring(walletId.length() - 4);
    }
}
