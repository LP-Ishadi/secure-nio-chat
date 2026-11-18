package com.securechat.client;

import com.securechat.addressing.NetworkAddressResolver;
import com.securechat.addressing.ResolvedEndpoint;
import com.securechat.config.ClientConfig;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.security.TlsContextFactory;
import com.securechat.udp.PresenceStatus;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Initial JavaFX desktop interface for the secure chat project. */
public final class SecureChatApplication extends Application {
    private static final String PUBLIC_CHAT = "Public Chat";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    private final ObservableList<String> contacts =
            FXCollections.observableArrayList(PUBLIC_CHAT);
    private final Map<String, List<ChatMessage>> conversations = new HashMap<>();
    private final Map<String, PresenceStatus> presenceByUser = new HashMap<>();
    private final ClientConfig clientConfig = ClientConfig.loadDefaultOrFallback();
    private final NetworkAddressResolver addressResolver =
            new NetworkAddressResolver();

    private Stage stage;
    private SecureChatClient client;
    private String username;
    private ResolvedEndpoint connectedEndpoint;
    private String selectedConversation = PUBLIC_CHAT;
    private boolean intentionalLogout;

    private ListView<String> contactList;
    private VBox messagesBox;
    private ScrollPane messageScroll;
    private Label conversationTitle;
    private Label conversationSubtitle;
    private Label profileName;
    private Label profileInitials;
    private Label profilePresence;
    private Label chatStatus;
    private TextField messageInput;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("QuickChat - Secure NIO Chat");
        stage.setMinWidth(1_050);
        stage.setMinHeight(680);
        stage.setOnCloseRequest(event -> closeClient());
        showLogin(null);
        stage.show();
    }

    private void showLogin(String initialMessage) {
        StackPane root = new StackPane();
        root.getStyleClass().addAll("app-root", "login-background");

        VBox card = new VBox(18);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(430);
        card.setAlignment(Pos.CENTER_LEFT);

        Label logo = new Label("●●●  QuickChat");
        logo.getStyleClass().add("login-logo");
        Label heading = new Label("Connect to your chat server");
        heading.getStyleClass().add("login-heading");
        Label description = new Label(
                "Enter the server address and choose a unique username."
        );
        description.getStyleClass().add("muted-label");
        description.setWrapText(true);

        TextField hostField = new TextField(clientConfig.serverHost());
        hostField.setPromptText("Server hostname");
        TextField portField = new TextField(
                Integer.toString(clientConfig.serverPort())
        );
        portField.setPromptText("Server port");
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username (3-20 characters)");
        hostField.getStyleClass().add("login-field");
        portField.getStyleClass().add("login-field");
        usernameField.getStyleClass().add("login-field");

        Button connectButton = new Button("Connect securely");
        connectButton.getStyleClass().add("primary-button");
        connectButton.setMaxWidth(Double.MAX_VALUE);

        Label status = new Label(initialMessage == null ? "" : initialMessage);
        status.getStyleClass().add("login-status");
        status.setWrapText(true);

        Runnable connectAction = () -> connectFromLogin(
                hostField,
                portField,
                usernameField,
                connectButton,
                status
        );
        connectButton.setOnAction(event -> connectAction.run());
        usernameField.setOnAction(event -> connectAction.run());

        card.getChildren().addAll(
                logo,
                heading,
                description,
                labelledField("SERVER", hostField),
                labelledField("PORT", portField),
                labelledField("USERNAME", usernameField),
                connectButton,
                status
        );
        root.getChildren().add(card);

        Scene scene = new Scene(root, 1_200, 760);
        applyStyles(scene);
        stage.setScene(scene);
    }

    private void connectFromLogin(
            TextField hostField,
            TextField portField,
            TextField usernameField,
            Button connectButton,
            Label status
    ) {
        String host = hostField.getText().trim();
        String requestedUsername = usernameField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException exception) {
            status.setText("Port must be a number between 1 and 65535.");
            return;
        }

        connectButton.setDisable(true);
        status.setText("Connecting to " + host + ":" + port + "...");
        intentionalLogout = false;
        presenceByUser.clear();
        SecureChatClient pendingClient;
        try {
            pendingClient = new SecureChatClient(
                    new GuiListener(),
                    TlsContextFactory.createClientContext(
                            clientConfig.trustStorePath(),
                            clientConfig.trustStorePassword().toCharArray()
                    )
            );
        } catch (IOException exception) {
            connectButton.setDisable(false);
            status.setText("TLS setup failed: " + exception.getMessage());
            return;
        }
        client = pendingClient;

        Thread.ofVirtual().name("chat-gui-connect").start(() -> {
            try {
                ResolvedEndpoint endpoint = addressResolver.resolve(host, port);
                pendingClient.connect(host, port, requestedUsername);
                Platform.runLater(() -> {
                    username = requestedUsername;
                    connectedEndpoint = endpoint;
                    showChat();
                    setChatStatus(
                            "Secure " + pendingClient.tlsProtocol()
                                    + " connection to " + endpoint.displayName()
                                    + " [" + endpoint.scope() + "]"
                    );
                    requestUsers();
                });
            } catch (LoginRejectedException exception) {
                pendingClient.close();
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    status.setText("Login rejected: " + exception.getMessage());
                });
            } catch (Exception exception) {
                pendingClient.close();
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    status.setText("Connection failed: " + exception.getMessage());
                });
            }
        });
    }

    private void showChat() {
        conversations.clear();
        contacts.setAll(PUBLIC_CHAT);
        selectedConversation = PUBLIC_CHAT;

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(createContactSidebar());
        root.setCenter(createConversationPanel());
        root.setRight(createProfilePanel());

        Scene scene = new Scene(root, 1_320, 800);
        applyStyles(scene);
        stage.setScene(scene);
        updateConversationSelection(PUBLIC_CHAT);
    }

    private Node createContactSidebar() {
        VBox sidebar = new VBox(16);
        sidebar.getStyleClass().add("left-sidebar");
        sidebar.setPrefWidth(285);

        Label brand = new Label("●●●  QuickChat");
        brand.getStyleClass().add("brand-label");
        Label account = new Label("Connected as " + username);
        account.getStyleClass().add("account-label");

        TextField search = new TextField();
        search.setPromptText("Search people...");
        search.getStyleClass().add("search-field");

        FilteredList<String> filteredContacts = new FilteredList<>(contacts);
        search.textProperty().addListener((observable, oldValue, value) -> {
            String query = value == null
                    ? ""
                    : value.toLowerCase(Locale.ROOT).trim();
            filteredContacts.setPredicate(name -> name.equals(PUBLIC_CHAT)
                    || name.toLowerCase(Locale.ROOT).contains(query));
        });

        contactList = new ListView<>(filteredContacts);
        contactList.getStyleClass().add("contact-list");
        contactList.setCellFactory(list -> new ContactCell());
        contactList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> {
                    if (selected != null) {
                        updateConversationSelection(selected);
                    }
                }
        );
        VBox.setVgrow(contactList, Priority.ALWAYS);

        sidebar.getChildren().addAll(brand, account, search, contactList);
        return sidebar;
    }

    private Node createConversationPanel() {
        BorderPane panel = new BorderPane();
        panel.getStyleClass().add("conversation-panel");

        VBox header = new VBox(3);
        header.getStyleClass().add("conversation-header");
        conversationTitle = new Label(PUBLIC_CHAT);
        conversationTitle.getStyleClass().add("conversation-title");
        conversationSubtitle = new Label("Messages visible to everyone online");
        conversationSubtitle.getStyleClass().add("muted-label");
        header.getChildren().addAll(conversationTitle, conversationSubtitle);
        panel.setTop(header);

        messagesBox = new VBox(14);
        messagesBox.getStyleClass().add("messages-box");
        messagesBox.setFillWidth(true);
        messageScroll = new ScrollPane(messagesBox);
        messageScroll.getStyleClass().add("message-scroll");
        messageScroll.setFitToWidth(true);
        messageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messagesBox.heightProperty().addListener(
                (observable, oldValue, value) -> messageScroll.setVvalue(1.0)
        );
        panel.setCenter(messageScroll);

        messageInput = new TextField();
        messageInput.setPromptText("Type a message...");
        messageInput.getStyleClass().add("message-input");
        messageInput.setOnAction(event -> sendCurrentMessage());
        HBox.setHgrow(messageInput, Priority.ALWAYS);

        Button sendButton = new Button("➤");
        sendButton.getStyleClass().add("send-button");
        sendButton.setOnAction(event -> sendCurrentMessage());
        HBox composer = new HBox(12, messageInput, sendButton);
        composer.getStyleClass().add("composer");
        composer.setAlignment(Pos.CENTER);
        panel.setBottom(composer);

        return panel;
    }

    private Node createProfilePanel() {
        VBox panel = new VBox(18);
        panel.getStyleClass().add("right-sidebar");
        panel.setPrefWidth(275);
        panel.setAlignment(Pos.TOP_CENTER);

        StackPane avatar = new StackPane();
        avatar.getStyleClass().add("large-avatar");
        profileInitials = new Label("#");
        profileInitials.getStyleClass().add("large-avatar-text");
        avatar.getChildren().add(profileInitials);

        profileName = new Label(PUBLIC_CHAT);
        profileName.getStyleClass().add("profile-name");
        profilePresence = new Label("Group conversation");
        profilePresence.getStyleClass().add("contact-subtitle");

        VBox media = new VBox(10);
        media.getStyleClass().add("media-placeholder");
        Label mediaHeading = new Label("Media");
        mediaHeading.getStyleClass().add("section-heading");
        Label mediaText = new Label(
                "Image and file sharing will appear here in a later phase."
        );
        mediaText.getStyleClass().add("muted-label");
        mediaText.setWrapText(true);
        media.getChildren().addAll(mediaHeading, mediaText);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        chatStatus = new Label("Connected");
        chatStatus.getStyleClass().add("chat-status");
        chatStatus.setWrapText(true);

        Button logout = new Button("Logout");
        logout.getStyleClass().add("logout-button");
        logout.setMaxWidth(Double.MAX_VALUE);
        logout.setOnAction(event -> logout());

        panel.getChildren().addAll(
                avatar,
                profileName,
                profilePresence,
                media,
                spacer,
                chatStatus,
                logout
        );
        return panel;
    }

    private void sendCurrentMessage() {
        String text = messageInput.getText();
        if (text == null || text.isBlank()) {
            setChatStatus("Message cannot be blank.");
            return;
        }

        try {
            if (selectedConversation.equals(PUBLIC_CHAT)) {
                client.sendPublicMessage(text);
            } else {
                client.sendPrivateMessage(selectedConversation, text);
            }
            messageInput.clear();
            setChatStatus("Sent");
        } catch (IOException exception) {
            setChatStatus("Send failed: " + exception.getMessage());
        }
    }

    private void handleServerMessage(ChatMessage message) {
        switch (message.type()) {
            case USER_LIST_RESPONSE -> updateContacts(message.payload());
            case USER_JOINED -> {
                addContact(message.payload());
                presenceByUser.put(message.payload(), PresenceStatus.ONLINE);
                refreshPresenceDisplay();
                storeAndDisplay(PUBLIC_CHAT, message);
            }
            case USER_LEFT -> {
                removeContact(message.payload());
                storeAndDisplay(PUBLIC_CHAT, message);
            }
            case PUBLIC_MESSAGE -> storeAndDisplay(PUBLIC_CHAT, message);
            case PRIVATE_MESSAGE -> {
                String conversation = username.equals(message.sender())
                        ? message.target()
                        : message.sender();
                addContact(conversation);
                storeAndDisplay(conversation, message);
            }
            case PRESENCE_UPDATE -> updatePresence(
                    message.target(),
                    message.payload()
            );
            case ERROR -> {
                storeAndDisplay(selectedConversation, message);
                setChatStatus(message.payload());
            }
            default -> setChatStatus("Server: " + message.type());
        }
    }

    private void updateContacts(String commaSeparatedUsers) {
        List<String> names = new ArrayList<>();
        if (commaSeparatedUsers != null && !commaSeparatedUsers.isBlank()) {
            for (String name : commaSeparatedUsers.split(",")) {
                if (!name.equalsIgnoreCase(username) && !name.isBlank()) {
                    names.add(name);
                    presenceByUser.putIfAbsent(name, PresenceStatus.ONLINE);
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        contacts.setAll(PUBLIC_CHAT);
        contacts.addAll(names);
        selectCurrentContact();
    }

    private void addContact(String name) {
        if (name == null || name.equalsIgnoreCase(username)) {
            return;
        }
        boolean exists = contacts.stream().anyMatch(
                current -> current.equalsIgnoreCase(name)
        );
        if (!exists) {
            contacts.add(name);
            presenceByUser.putIfAbsent(name, PresenceStatus.ONLINE);
            sortContacts();
        }
    }

    private void removeContact(String name) {
        contacts.removeIf(current -> !current.equals(PUBLIC_CHAT)
                && current.equalsIgnoreCase(name));
        presenceByUser.remove(name);
        if (selectedConversation.equalsIgnoreCase(name)) {
            updateConversationSelection(PUBLIC_CHAT);
        }
    }

    private void sortContacts() {
        List<String> users = contacts.stream()
                .filter(name -> !name.equals(PUBLIC_CHAT))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        contacts.setAll(PUBLIC_CHAT);
        contacts.addAll(users);
        selectCurrentContact();
    }

    private void selectCurrentContact() {
        if (contactList != null) {
            contactList.getSelectionModel().select(selectedConversation);
        }
    }

    private void storeAndDisplay(String conversation, ChatMessage message) {
        conversations.computeIfAbsent(conversation, key -> new ArrayList<>())
                .add(message);
        if (conversation.equals(selectedConversation)) {
            messagesBox.getChildren().add(createMessageRow(message));
        }
    }

    private void updateConversationSelection(String conversation) {
        selectedConversation = conversation;
        if (conversationTitle != null) {
            conversationTitle.setText(conversation);
            conversationSubtitle.setText(conversation.equals(PUBLIC_CHAT)
                    ? "Messages visible to everyone online"
                    : "Private conversation");
            profileName.setText(conversation);
            profileInitials.setText(initials(conversation));
            updateProfilePresence();
            refreshMessages();
            selectCurrentContact();
        }
    }

    private void updatePresence(String name, String statusText) {
        if (name == null || name.equalsIgnoreCase(username)) {
            return;
        }

        try {
            PresenceStatus status = PresenceStatus.valueOf(statusText);
            presenceByUser.put(name, status);
            addContact(name);
            refreshPresenceDisplay();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            setChatStatus("Ignored an invalid presence update from the server.");
        }
    }

    private void refreshPresenceDisplay() {
        if (contactList != null) {
            contactList.refresh();
        }
        updateProfilePresence();
    }

    private void updateProfilePresence() {
        if (profilePresence == null) {
            return;
        }
        if (selectedConversation.equals(PUBLIC_CHAT)) {
            setPresenceLabel(profilePresence, null, true);
            return;
        }
        setPresenceLabel(
                profilePresence,
                presenceByUser.getOrDefault(
                        selectedConversation,
                        PresenceStatus.UNREACHABLE
                ),
                false
        );
    }

    private static void setPresenceLabel(
            Label label,
            PresenceStatus status,
            boolean groupConversation
    ) {
        label.getStyleClass().removeAll(
                "online-label",
                "away-label",
                "unreachable-label",
                "offline-label",
                "contact-subtitle"
        );
        if (groupConversation) {
            label.setText("Group conversation");
            label.getStyleClass().add("contact-subtitle");
            return;
        }

        PresenceStatus actual = status == null
                ? PresenceStatus.UNREACHABLE
                : status;
        label.setText("● " + switch (actual) {
            case ONLINE -> "Online";
            case AWAY -> "Away";
            case UNREACHABLE -> "Unreachable";
            case OFFLINE -> "Offline";
        });
        label.getStyleClass().add(actual.name().toLowerCase(Locale.ROOT) + "-label");
    }

    private void refreshMessages() {
        messagesBox.getChildren().clear();
        conversations.getOrDefault(selectedConversation, List.of())
                .stream()
                .sorted(Comparator.comparingLong(ChatMessage::timestamp))
                .map(this::createMessageRow)
                .forEach(messagesBox.getChildren()::add);
    }

    private Node createMessageRow(ChatMessage message) {
        if (message.type() == MessageType.USER_JOINED
                || message.type() == MessageType.USER_LEFT
                || message.type() == MessageType.ERROR) {
            String text = switch (message.type()) {
                case USER_JOINED -> message.payload() + " joined the chat";
                case USER_LEFT -> message.payload() + " left the chat";
                default -> message.payload();
            };
            Label system = new Label(text);
            system.getStyleClass().add("system-message");
            system.setWrapText(true);
            HBox row = new HBox(system);
            row.setAlignment(Pos.CENTER);
            return row;
        }

        boolean ownMessage = username.equals(message.sender());
        Label sender = new Label(ownMessage ? "You" : message.sender());
        sender.getStyleClass().add("message-sender");
        Label content = new Label(message.payload());
        content.getStyleClass().add("message-content");
        content.setWrapText(true);
        content.setMaxWidth(460);
        Label time = new Label(formatTime(message.timestamp()));
        time.getStyleClass().add("message-time");

        VBox bubble = new VBox(4, sender, content, time);
        bubble.getStyleClass().add(
                ownMessage ? "outgoing-bubble" : "incoming-bubble"
        );
        HBox row = new HBox(bubble);
        row.setAlignment(ownMessage ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    private void requestUsers() {
        try {
            client.requestUserList();
        } catch (IOException exception) {
            setChatStatus("Could not request users: " + exception.getMessage());
        }
    }

    private void logout() {
        intentionalLogout = true;
        closeClient();
        username = null;
        connectedEndpoint = null;
        showLogin("Disconnected successfully.");
    }

    private void closeClient() {
        SecureChatClient activeClient = client;
        client = null;
        if (activeClient != null) {
            activeClient.close();
        }
    }

    private void setChatStatus(String message) {
        if (chatStatus != null) {
            chatStatus.setText(message);
        }
    }

    private static VBox labelledField(String labelText, TextField field) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        return new VBox(7, label, field);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        if (name.equals(PUBLIC_CHAT)) {
            return "#";
        }
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        if (parts.length == 1) {
            return first;
        }
        return first + parts[parts.length - 1]
                .substring(0, 1)
                .toUpperCase(Locale.ROOT);
    }

    private static String formatTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }

    private void applyStyles(Scene scene) {
        scene.getStylesheets().add(
                getClass().getResource("/com/securechat/client/chat.css")
                        .toExternalForm()
        );
    }

    @Override
    public void stop() {
        intentionalLogout = true;
        closeClient();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private final class GuiListener implements ChatClientListener {
        @Override
        public void onMessage(ChatMessage message) {
            Platform.runLater(() -> handleServerMessage(message));
        }

        @Override
        public void onConnectionError(String message, Throwable cause) {
            Platform.runLater(() -> setChatStatus(message));
        }

        @Override
        public void onDisconnected() {
            Platform.runLater(() -> {
                if (!intentionalLogout && stage.getScene() != null) {
                    username = null;
                    connectedEndpoint = null;
                    showLogin("Connection to the server was closed.");
                }
            });
        }
    }

    private final class ContactCell extends ListCell<String> {
        @Override
        protected void updateItem(String name, boolean empty) {
            super.updateItem(name, empty);
            if (empty || name == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            Label initials = new Label(initials(name));
            initials.getStyleClass().add("contact-avatar-text");
            StackPane avatar = new StackPane(initials);
            avatar.getStyleClass().add("contact-avatar");

            Label title = new Label(name);
            title.getStyleClass().add("contact-name");
            Label status = new Label();
            setPresenceLabel(
                    status,
                    presenceByUser.get(name),
                    name.equals(PUBLIC_CHAT)
            );
            VBox labels = new VBox(2, title, status);
            HBox graphic = new HBox(12, avatar, labels);
            graphic.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(graphic);
        }
    }
}
