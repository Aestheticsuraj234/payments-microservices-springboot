package dev.payments.notification;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Sends one notification, at most once per event and person, as far as we
 * can make it so.
 *
 * <p>The record of the email and the email itself are two systems again, so
 * there's a choice of what can go wrong:
 * <ul>
 *   <li>record, commit, then send: a crash before sending loses the email.</li>
 *   <li>record, send, then commit (what we do): a crash after sending and
 *       before the commit sends it again on redelivery.</li>
 * </ul>
 * A duplicate "you received $10" is annoying; a missing one is worse. We pick
 * the duplicate, and make it rare.
 */
@Component
class Notifier {

    private final JdbcClient jdbc;
    private final AuthClient users;
    private final JavaMailSender mail;
    private final NotificationProperties properties;

    Notifier(JdbcClient jdbc, AuthClient users, JavaMailSender mail, NotificationProperties properties) {
        this.jdbc = jdbc;
        this.users = users;
        this.mail = mail;
        this.properties = properties;
    }

    /** Returns true if it sent the email, false if this event already notified this person. */
    @Transactional
    public boolean deliver(Notification n) {
        String email = emailOf(n);
        // The primary key (event, person) decides. A second delivery of the
        // same event, even at the same moment, inserts nothing and skips.
        int inserted = jdbc.sql("""
                        INSERT INTO notifications (event_id, user_id, recipient, subject)
                        VALUES (:event, :user, :recipient, :subject)
                        ON CONFLICT (event_id, user_id) DO NOTHING
                        """)
                .param("event", n.eventId()).param("user", n.userId())
                .param("recipient", email).param("subject", n.subject())
                .update();
        if (inserted == 0) {
            return false;
        }
        var message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(n.subject());
        message.setText(n.body());
        mail.send(message); // throws if SMTP is down: the row rolls back, and the retry sends it
        return true;
    }

    private String emailOf(Notification n) {
        try {
            return users.user(n.userId()).email();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new PermanentException("user " + n.userId() + " does not exist", e);
            }
            throw e;
        }
    }
}
