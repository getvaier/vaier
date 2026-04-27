package net.vaier.adapter.driven;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForSendingNotificationEmail;
import net.vaier.domain.port.ForSendingTestEmail;
import net.vaier.domain.port.ForVerifyingSmtpCredentials;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JakartaMailSmtpVerifier implements ForVerifyingSmtpCredentials, ForSendingTestEmail, ForSendingNotificationEmail {

    @Override
    public void verify(String host, int port, String username, String password) {
        Session session = Session.getInstance(smtpProps(host, port));
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, port, username, password);
            log.info("SMTP credentials verified for host {}", host);
        } catch (NoSuchProviderException e) {
            throw new IllegalStateException("SMTP provider not available", e);
        } catch (MessagingException e) {
            log.warn("SMTP verification failed for {}@{}:{} — {}", username, host, port, e.getMessage());
            throw new InvalidSmtpCredentialsException(summarize(e), e);
        }
    }

    @Override
    public void sendTestEmail(String host, int port, String username, String password,
                              String sender, String recipient) {
        Session session = Session.getInstance(smtpProps(host, port));
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, port, username, password);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(sender != null && !sender.isBlank() ? sender : username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
            message.setSubject("Vaier SMTP test");
            message.setText("""
                    This is a test message from Vaier confirming that your SMTP
                    settings are working correctly.

                    If you received this, outgoing notifications from Vaier
                    will be delivered through this server.
                    """);
            transport.sendMessage(message, message.getAllRecipients());
            log.info("Sent SMTP test email to {} via {}", recipient, host);
        } catch (NoSuchProviderException e) {
            throw new IllegalStateException("SMTP provider not available", e);
        } catch (MessagingException e) {
            log.warn("SMTP test email failed for {}@{}:{} → {} — {}", username, host, port, recipient, e.getMessage());
            throw new InvalidSmtpCredentialsException(summarize(e), e);
        }
    }

    @Override
    public void sendEmail(String host, int port, String username, String password,
                          String sender, List<String> recipients, String subject, String body) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        Session session = Session.getInstance(smtpProps(host, port));
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, port, username, password);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(sender != null && !sender.isBlank() ? sender : username));
            message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(String.join(",", recipients)));
            message.setSubject(subject);
            message.setText(body);
            transport.sendMessage(message, message.getAllRecipients());
            log.info("Sent notification email to {} via {}", recipients, host);
        } catch (NoSuchProviderException e) {
            throw new IllegalStateException("SMTP provider not available", e);
        } catch (MessagingException e) {
            log.warn("Notification email failed for {}@{}:{} → {} — {}",
                    username, host, port, recipients, e.getMessage());
            throw new RuntimeException(summarize(e), e);
        }
    }

    private static Properties smtpProps(String host, int port) {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        return props;
    }

    private static String summarize(MessagingException e) {
        String message = e.getMessage();
        if (message == null) return "SMTP verification failed";
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline).trim() : message.trim();
    }

    public static class InvalidSmtpCredentialsException extends RuntimeException {
        public InvalidSmtpCredentialsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
