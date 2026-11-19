package com.securechat.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationManagerTest {

    private NotificationManager notificationManager;

    @BeforeEach
    void setUp() {
        notificationManager = new NotificationManager();
    }

    @Test
    void testShouldNotifyWhenConversationNotActive() {
        notificationManager.setActiveConversationId("bob");

        // Message from alice should be notified
        assertTrue(notificationManager.shouldNotify("alice", "req-1"));

        // Message from bob (currently active conversation) should be suppressed
        assertFalse(notificationManager.shouldNotify("bob", "req-2"));
        assertFalse(notificationManager.shouldNotify("BOB", "req-3"));
    }

    @Test
    void testDuplicateNotificationsSuppressedByRequestId() {
        notificationManager.setActiveConversationId(null);

        // First notification with req-100 is allowed
        assertTrue(notificationManager.shouldNotify("alice", "req-100"));

        // Duplicate delivery with same requestId is suppressed
        assertFalse(notificationManager.shouldNotify("alice", "req-100"));

        // Different requestId is allowed
        assertTrue(notificationManager.shouldNotify("alice", "req-101"));
    }

    @Test
    void testPrivacyModeToggle() {
        assertFalse(notificationManager.isPrivacyModeEnabled());

        notificationManager.setPrivacyModeEnabled(true);
        assertTrue(notificationManager.isPrivacyModeEnabled());

        notificationManager.setPrivacyModeEnabled(false);
        assertFalse(notificationManager.isPrivacyModeEnabled());
    }

    @Test
    void testFormatPreview() {
        assertEquals("", NotificationManager.formatPreview(null));
        assertEquals("", NotificationManager.formatPreview("   "));
        assertEquals("Hello world", NotificationManager.formatPreview("Hello   world"));

        String longMsg = "This is a very long text that exceeds sixty-five characters in total length for preview formatting.";
        String preview = NotificationManager.formatPreview(longMsg);
        assertTrue(preview.endsWith("..."));
        assertTrue(preview.length() <= 65);
    }

    @Test
    void testFormatTime() {
        long epoch = 1700000000000L;
        String formatted = NotificationManager.formatTime(epoch);
        assertTrue(formatted != null && !formatted.isBlank());
        assertTrue(formatted.contains("AM") || formatted.contains("PM"));
    }

    @Test
    void testNotificationClickCallback() {
        AtomicReference<String> clickedConv = new AtomicReference<>();
        AtomicBoolean clickedIsGroup = new AtomicBoolean(false);

        notificationManager.setOnNotificationClick((convId, isGroup) -> {
            clickedConv.set(convId);
            clickedIsGroup.set(isGroup);
        });

        assertEquals(null, clickedConv.get());
        assertFalse(clickedIsGroup.get());
    }
}
