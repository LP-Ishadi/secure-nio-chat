package com.securechat.client;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Manages WhatsApp-style desktop popup notifications for personal and group messages.
 * Supports privacy mode, active chat suppression, duplicate filtering, and click-to-activate.
 */
public final class NotificationManager {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("hh:mm a");

    private final Set<String> displayedRequestIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private volatile String activeConversationId = null;
    private volatile boolean privacyModeEnabled = false;
    private volatile BiConsumer<String, Boolean> onNotificationClick;

    public NotificationManager() {
        this(null);
    }

    public NotificationManager(BiConsumer<String, Boolean> onNotificationClick) {
        this.onNotificationClick = onNotificationClick;
    }

    public void setOnNotificationClick(BiConsumer<String, Boolean> onNotificationClick) {
        this.onNotificationClick = onNotificationClick;
    }

    public void setActiveConversationId(String activeConversationId) {
        this.activeConversationId = activeConversationId;
    }

    public String activeConversationId() {
        return activeConversationId;
    }

    public void setPrivacyModeEnabled(boolean privacyModeEnabled) {
        this.privacyModeEnabled = privacyModeEnabled;
    }

    public boolean isPrivacyModeEnabled() {
        return privacyModeEnabled;
    }

    /**
     * Determines whether a notification should be presented based on active chat and deduplication rules.
     */
    public boolean shouldNotify(String conversationId, String requestId) {
        if (conversationId != null && activeConversationId != null
                && conversationId.equalsIgnoreCase(activeConversationId)) {
            return false;
        }
        if (requestId != null && !requestId.isBlank()) {
            return displayedRequestIds.add(requestId);
        }
        return true;
    }

    /**
     * Shows a popup notification for an incoming message.
     */
    public void notifyMessage(
            String sender,
            String content,
            String groupName,
            String conversationId,
            boolean isGroup,
            long timestamp,
            String requestId
    ) {
        if (!shouldNotify(conversationId, requestId)) {
            return;
        }

        String preview = formatPreview(content);
        String timeStr = formatTime(timestamp);

        Platform.runLater(() -> displayPopup(
                sender,
                preview,
                groupName,
                conversationId,
                isGroup,
                timeStr
        ));
    }

    private void displayPopup(
            String sender,
            String preview,
            String groupName,
            String conversationId,
            boolean isGroup,
            String timeStr
    ) {
        try {
            Stage popup = new Stage();
            popup.initStyle(StageStyle.TRANSPARENT);
            popup.setAlwaysOnTop(true);

            VBox root = new VBox(6);
            root.getStyleClass().add("notification-toast");
            root.setPadding(new Insets(14, 18, 14, 18));
            root.setPrefWidth(330);
            root.setMaxWidth(360);

            // Header line: sender / group + time
            String titleText = isGroup && groupName != null && !groupName.isBlank()
                    ? sender + " in " + groupName
                    : sender;

            Label titleLabel = new Label(titleText);
            titleLabel.getStyleClass().add("notification-title");
            HBox.setHgrow(titleLabel, Priority.ALWAYS);

            Label timeLabel = new Label(timeStr);
            timeLabel.getStyleClass().add("notification-time");

            HBox header = new HBox(8, titleLabel, timeLabel);
            header.setAlignment(Pos.CENTER_LEFT);

            // Message preview (masked in privacy mode)
            String displayText = privacyModeEnabled
                    ? (isGroup ? "New group message received" : "New personal message received")
                    : preview;

            Label contentLabel = new Label(displayText);
            contentLabel.getStyleClass().add("notification-preview");
            contentLabel.setWrapText(true);
            contentLabel.setMaxHeight(48);

            root.getChildren().addAll(header, contentLabel);

            // Click handling
            root.setOnMouseClicked(event -> {
                popup.close();
                BiConsumer<String, Boolean> handler = onNotificationClick;
                if (handler != null && conversationId != null) {
                    handler.accept(conversationId, isGroup);
                }
            });

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            try {
                scene.getStylesheets().add(
                        getClass().getResource("/com/securechat/client/chat.css").toExternalForm()
                );
            } catch (Exception ignored) {
            }

            popup.setScene(scene);

            // Position at bottom-right of visual screen bounds
            try {
                Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                popup.setX(bounds.getMaxX() - 350);
                popup.setY(bounds.getMaxY() - 110);
            } catch (Exception ignored) {
            }

            // Auto-dismiss with fade out after 4 seconds
            PauseTransition delay = new PauseTransition(Duration.seconds(4.0));
            delay.setOnFinished(e -> {
                FadeTransition fade = new FadeTransition(Duration.millis(300), root);
                fade.setFromValue(1.0);
                fade.setToValue(0.0);
                fade.setOnFinished(fe -> popup.close());
                fade.play();
            });

            popup.show();
            delay.play();
        } catch (Exception ex) {
            System.err.println("Could not display notification popup: " + ex.getMessage());
        }
    }

    public static String formatPreview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 65
                ? singleLine.substring(0, 62) + "..."
                : singleLine;
    }

    public static String formatTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp > 0 ? timestamp : System.currentTimeMillis())
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }
}
