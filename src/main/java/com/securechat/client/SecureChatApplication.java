package com.securechat.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securechat.addressing.NetworkAddressResolver;
import com.securechat.addressing.ResolvedEndpoint;
import com.securechat.config.ClientConfig;
import com.securechat.database.StoredGroupMessage;
import com.securechat.database.StoredMessage;
import com.securechat.model.ChatGroup;
import com.securechat.model.GroupMember;
import com.securechat.model.GroupMemberRole;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import com.securechat.model.ConversationSummary;
import com.securechat.model.GroupMemberRole;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JavaFX desktop interface for the secure chat project, supporting
 * direct messaging, public chat, and group management.
 */
public final class SecureChatApplication extends Application {
    private static final String PUBLIC_CHAT = "Public Chat";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    private final ObservableList<String> contacts =
            FXCollections.observableArrayList(PUBLIC_CHAT);
    private final ObservableList<ChatGroup> userGroups =
            FXCollections.observableArrayList();
    private final ObservableList<GroupMember> currentGroupMembers =
            FXCollections.observableArrayList();
    private final Map<String, List<ChatMessage>> conversations = new HashMap<>();
    private final Map<String, Integer> groupHistoryOffsets = new HashMap<>();
    private final Map<String, PresenceStatus> presenceByUser = new HashMap<>();
    private final ClientConfig clientConfig = ClientConfig.loadDefaultOrFallback();
    private final NetworkAddressResolver addressResolver =
            new NetworkAddressResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final NotificationManager notificationManager = new NotificationManager();
    private final Map<String, Integer> unreadCounts = new ConcurrentHashMap<>();
    private final Map<String, String> latestMessagePreviews = new ConcurrentHashMap<>();
    private final Map<String, Long> latestMessageTimestamps = new ConcurrentHashMap<>();

    private Stage stage;
    private SecureChatClient client;
    private String username;
    private ResolvedEndpoint connectedEndpoint;
    private String selectedConversation = PUBLIC_CHAT;
    private ChatGroup selectedGroup;
    private boolean viewingGroups = false;
    private boolean intentionalLogout;

    private ListView<String> contactList;
    private ListView<ChatGroup> groupListView;
    private FilteredList<String> filteredContacts;
    private FilteredList<ChatGroup> filteredGroups;
    private Button tabChatsButton;
    private Button tabGroupsButton;
    private Button createGroupButton;
    private TextField searchField;
    private VBox onlineUsersSection;
    private HBox onlineUsersBox;

    private VBox messagesBox;
    private ScrollPane messageScroll;
    private Label conversationTitle;
    private Label conversationSubtitle;
    private Label profileName;
    private Label profileInitials;
    private Label profilePresence;
    private Label chatStatus;
    private TextField messageInput;

    private VBox mediaBox;
    private VBox groupDetailsBox;
    private VBox groupMembersBox;
    private Label membersHeading;
    private Label groupIdLabel;
    private Button addMemberButton;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("QuickChat - Secure NIO Chat");
        stage.setMinWidth(1_050);
        stage.setMinHeight(680);
        stage.setOnCloseRequest(event -> closeClient());

        notificationManager.setOnNotificationClick((convId, isGroup) -> Platform.runLater(() -> {
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            stage.toFront();
            if (isGroup) {
                switchToGroupsTab();
                userGroups.stream()
                        .filter(g -> g.groupId().equals(convId))
                        .findFirst()
                        .ifPresent(grp -> {
                            groupListView.getSelectionModel().select(grp);
                            selectGroup(grp);
                        });
            } else {
                switchToChatsTab();
                contactList.getSelectionModel().select(convId);
                updateConversationSelection(convId);
            }
        }));

        showLogin(null);
        stage.show();
    }

    private void showLogin(String initialMessage) {
        showLogin(initialMessage, clientConfig.serverHost(), Integer.toString(clientConfig.serverPort()), "");
    }

    private void showLogin(
            String initialMessage,
            String initialHost,
            String initialPort,
            String initialUsername
    ) {
        StackPane root = new StackPane();
        root.getStyleClass().addAll("app-root", "login-background");

        VBox card = new VBox(15);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(440);
        card.setAlignment(Pos.CENTER_LEFT);

        Label logo = new Label("●●●  QuickChat");
        logo.getStyleClass().add("login-logo");
        Label heading = new Label("Welcome back");
        heading.getStyleClass().add("login-heading");
        Label description = new Label(
                "Sign in to access your secure chat conversations."
        );
        description.getStyleClass().add("muted-label");
        description.setWrapText(true);

        TextField hostField = new TextField(initialHost != null && !initialHost.isBlank()
                ? initialHost : clientConfig.serverHost());
        hostField.setPromptText("Server hostname");
        TextField portField = new TextField(initialPort != null && !initialPort.isBlank()
                ? initialPort : Integer.toString(clientConfig.serverPort()));
        portField.setPromptText("Server port");
        TextField usernameField = new TextField(initialUsername != null ? initialUsername : "");
        usernameField.setPromptText("Username (3-20 characters)");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Enter your password");

        hostField.getStyleClass().add("login-field");
        portField.getStyleClass().add("login-field");
        usernameField.getStyleClass().add("login-field");
        passwordField.getStyleClass().add("login-field");

        Button loginButton = new Button("Sign In");
        loginButton.getStyleClass().add("primary-button");
        loginButton.setMaxWidth(Double.MAX_VALUE);

        Hyperlink registerLink = new Hyperlink("Don't have an account? Register here");
        registerLink.getStyleClass().add("auth-switch-link");
        registerLink.setOnAction(event -> showRegister(
                null,
                hostField.getText().trim(),
                portField.getText().trim(),
                usernameField.getText().trim()
        ));

        Label status = new Label(initialMessage == null ? "" : initialMessage);
        status.getStyleClass().add("login-status");
        status.setWrapText(true);

        Runnable loginAction = () -> connectFromLogin(
                hostField,
                portField,
                usernameField,
                passwordField,
                loginButton,
                status
        );
        loginButton.setOnAction(event -> loginAction.run());
        usernameField.setOnAction(event -> passwordField.requestFocus());
        passwordField.setOnAction(event -> loginAction.run());

        card.getChildren().addAll(
                logo,
                heading,
                description,
                labelledField("SERVER", hostField),
                labelledField("PORT", portField),
                labelledField("USERNAME", usernameField),
                labelledField("PASSWORD", passwordField),
                loginButton,
                registerLink,
                status
        );
        root.getChildren().add(card);

        Scene scene = new Scene(root, 1_200, 760);
        applyStyles(scene);
        stage.setScene(scene);
    }

    private void showRegister(
            String initialMessage,
            String initialHost,
            String initialPort,
            String initialUsername
    ) {
        StackPane root = new StackPane();
        root.getStyleClass().addAll("app-root", "login-background");

        VBox card = new VBox(15);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(440);
        card.setAlignment(Pos.CENTER_LEFT);

        Label logo = new Label("●●●  QuickChat");
        logo.getStyleClass().add("login-logo");
        Label heading = new Label("Create an account");
        heading.getStyleClass().add("login-heading");
        Label description = new Label(
                "Register a new account on your secure chat server."
        );
        description.getStyleClass().add("muted-label");
        description.setWrapText(true);

        TextField hostField = new TextField(initialHost != null && !initialHost.isBlank()
                ? initialHost : clientConfig.serverHost());
        hostField.setPromptText("Server hostname");
        TextField portField = new TextField(initialPort != null && !initialPort.isBlank()
                ? initialPort : Integer.toString(clientConfig.serverPort()));
        portField.setPromptText("Server port");
        TextField usernameField = new TextField(initialUsername != null ? initialUsername : "");
        usernameField.setPromptText("Username (3-20 characters)");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password (at least 8 characters)");
        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Confirm password");

        hostField.getStyleClass().add("login-field");
        portField.getStyleClass().add("login-field");
        usernameField.getStyleClass().add("login-field");
        passwordField.getStyleClass().add("login-field");
        confirmPasswordField.getStyleClass().add("login-field");

        Button registerButton = new Button("Register");
        registerButton.getStyleClass().add("primary-button");
        registerButton.setMaxWidth(Double.MAX_VALUE);

        Hyperlink loginLink = new Hyperlink("Already have an account? Sign In");
        loginLink.getStyleClass().add("auth-switch-link");
        loginLink.setOnAction(event -> showLogin(
                null,
                hostField.getText().trim(),
                portField.getText().trim(),
                usernameField.getText().trim()
        ));

        Label status = new Label(initialMessage == null ? "" : initialMessage);
        status.getStyleClass().add("login-status");
        status.setWrapText(true);

        Runnable registerAction = () -> registerFromForm(
                hostField,
                portField,
                usernameField,
                passwordField,
                confirmPasswordField,
                registerButton,
                status
        );
        registerButton.setOnAction(event -> registerAction.run());
        confirmPasswordField.setOnAction(event -> registerAction.run());

        card.getChildren().addAll(
                logo,
                heading,
                description,
                labelledField("SERVER", hostField),
                labelledField("PORT", portField),
                labelledField("USERNAME", usernameField),
                labelledField("PASSWORD", passwordField),
                labelledField("CONFIRM PASSWORD", confirmPasswordField),
                registerButton,
                loginLink,
                status
        );
        root.getChildren().add(card);

        Scene scene = new Scene(root, 1_200, 760);
        applyStyles(scene);
        stage.setScene(scene);
    }

    private void registerFromForm(
            TextField hostField,
            TextField portField,
            TextField usernameField,
            PasswordField passwordField,
            PasswordField confirmPasswordField,
            Button registerButton,
            Label status
    ) {
        String host = hostField.getText().trim();
        String requestedUsername = usernameField.getText().trim();
        String password = passwordField.getText();
        String confirmPassword = confirmPasswordField.getText();

        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException exception) {
            status.setText("Port must be a number between 1 and 65535.");
            return;
        }

        if (requestedUsername.isBlank()) {
            status.setText("Username must not be blank.");
            return;
        }

        if (password == null || password.length() < 8) {
            status.setText("Password must contain at least 8 characters.");
            return;
        }

        if (!password.equals(confirmPassword)) {
            status.setText("Passwords do not match.");
            return;
        }

        registerButton.setDisable(true);
        status.setText("Registering account on " + host + ":" + port + "...");

        Thread.ofVirtual().name("chat-gui-register").start(() -> {
            try {
                SecureChatClient tempClient = new SecureChatClient(
                        new GuiListener(),
                        TlsContextFactory.createClientContext(
                                clientConfig.trustStorePath(),
                                clientConfig.trustStorePassword().toCharArray()
                        )
                );
                ChatMessage response = tempClient.register(host, port, requestedUsername, password);
                Platform.runLater(() -> {
                    registerButton.setDisable(false);
                    if (response.type() == MessageType.REGISTER_SUCCESS) {
                        showLogin(
                                "Registration successful! You can now sign in.",
                                host,
                                Integer.toString(port),
                                requestedUsername
                        );
                    } else {
                        status.setText("Registration failed: " + response.payload());
                    }
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    registerButton.setDisable(false);
                    status.setText("Registration error: " + exception.getMessage());
                });
            }
        });
    }

    private void connectFromLogin(
            TextField hostField,
            TextField portField,
            TextField usernameField,
            PasswordField passwordField,
            Button connectButton,
            Label status
    ) {
        String host = hostField.getText().trim();
        String requestedUsername = usernameField.getText().trim();
        String password = passwordField.getText();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException exception) {
            status.setText("Port must be a number between 1 and 65535.");
            return;
        }

        if (requestedUsername.isBlank()) {
            status.setText("Username must not be blank.");
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
                pendingClient.connect(host, port, requestedUsername, password);
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
                    try {
                        client.requestHistory(null, 50);
                    } catch (IOException exception) {
                        setChatStatus("Could not fetch history: " + exception.getMessage());
                    }
                    try {
                        client.requestGroupList();
                    } catch (IOException exception) {
                        setChatStatus("Could not fetch groups: " + exception.getMessage());
                    }
                    try {
                        client.requestConversationSummaries();
                    } catch (IOException exception) {
                        setChatStatus("Could not fetch summaries: " + exception.getMessage());
                    }
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
        userGroups.clear();
        currentGroupMembers.clear();
        groupHistoryOffsets.clear();
        unreadCounts.clear();
        latestMessagePreviews.clear();
        latestMessageTimestamps.clear();
        selectedGroup = null;
        viewingGroups = false;
        selectedConversation = PUBLIC_CHAT;
        notificationManager.setActiveConversationId(PUBLIC_CHAT);

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
        VBox sidebar = new VBox(14);
        sidebar.getStyleClass().add("left-sidebar");
        sidebar.setPrefWidth(295);

        Label brand = new Label("●●●  QuickChat");
        brand.getStyleClass().add("brand-label");
        Label account = new Label("Connected as " + username);
        account.getStyleClass().add("account-label");

        // Privacy toggle button
        Button privacyButton = new Button("🔒 Privacy: OFF");
        privacyButton.getStyleClass().add("privacy-toggle-btn");
        privacyButton.setOnAction(e -> {
            boolean enabled = !notificationManager.isPrivacyModeEnabled();
            notificationManager.setPrivacyModeEnabled(enabled);
            if (enabled) {
                privacyButton.setText("🔒 Privacy: ON");
                privacyButton.getStyleClass().add("privacy-toggle-btn-active");
            } else {
                privacyButton.setText("🔒 Privacy: OFF");
                privacyButton.getStyleClass().remove("privacy-toggle-btn-active");
            }
        });

        HBox accountBox = new HBox(8, account, privacyButton);
        accountBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(account, Priority.ALWAYS);

        // Navigation tabs for Chats vs Groups
        tabChatsButton = new Button("Chats");
        tabChatsButton.getStyleClass().addAll("nav-tab-button", "nav-tab-button-active");

        tabGroupsButton = new Button("Groups (0)");
        tabGroupsButton.getStyleClass().add("nav-tab-button");

        createGroupButton = new Button("+ Group");
        createGroupButton.getStyleClass().add("create-group-btn");
        createGroupButton.setOnAction(e -> showCreateGroupDialog());

        HBox.setHgrow(tabChatsButton, Priority.ALWAYS);
        HBox.setHgrow(tabGroupsButton, Priority.ALWAYS);
        tabChatsButton.setMaxWidth(Double.MAX_VALUE);
        tabGroupsButton.setMaxWidth(Double.MAX_VALUE);

        HBox tabs = new HBox(6, tabChatsButton, tabGroupsButton, createGroupButton);
        tabs.setAlignment(Pos.CENTER_LEFT);

        // Online users horizontal section
        Label onlineHeader = new Label("ONLINE USERS");
        onlineHeader.getStyleClass().add("field-label");

        onlineUsersBox = new HBox(10);
        onlineUsersBox.setAlignment(Pos.CENTER_LEFT);

        ScrollPane onlineScroll = new ScrollPane(onlineUsersBox);
        onlineScroll.getStyleClass().add("online-strip-scroll");
        onlineScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        onlineScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        onlineScroll.setFitToHeight(true);
        onlineScroll.setPrefHeight(68);
        onlineScroll.setMaxHeight(75);

        onlineUsersSection = new VBox(4, onlineHeader, onlineScroll);
        onlineUsersSection.getStyleClass().add("online-strip-container");

        searchField = new TextField();
        searchField.setPromptText("Search people...");
        searchField.getStyleClass().add("search-field");

        filteredContacts = new FilteredList<>(contacts);
        filteredGroups = new FilteredList<>(userGroups);

        searchField.textProperty().addListener((observable, oldValue, value) -> {
            String query = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
            if (viewingGroups) {
                filteredGroups.setPredicate(g -> g.name().toLowerCase(Locale.ROOT).contains(query)
                        || g.description().toLowerCase(Locale.ROOT).contains(query));
            } else {
                filteredContacts.setPredicate(name -> name.equals(PUBLIC_CHAT)
                        || name.toLowerCase(Locale.ROOT).contains(query));
            }
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

        groupListView = new ListView<>(filteredGroups);
        groupListView.getStyleClass().add("contact-list");
        groupListView.setCellFactory(list -> new GroupCell());
        groupListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> {
                    if (selected != null) {
                        selectGroup(selected);
                    }
                }
        );
        VBox.setVgrow(groupListView, Priority.ALWAYS);

        StackPane listContainer = new StackPane(contactList, groupListView);
        groupListView.setVisible(false);
        groupListView.setManaged(false);
        VBox.setVgrow(listContainer, Priority.ALWAYS);

        tabChatsButton.setOnAction(e -> switchToChatsTab());
        tabGroupsButton.setOnAction(e -> switchToGroupsTab());

        sidebar.getChildren().addAll(brand, accountBox, tabs, onlineUsersSection, searchField, listContainer);
        refreshOnlineUsersStrip();
        return sidebar;
    }

    private void refreshOnlineUsersStrip() {
        if (onlineUsersBox == null) {
            return;
        }
        onlineUsersBox.getChildren().clear();

        List<String> onlineList = contacts.stream()
                .filter(name -> !name.equals(PUBLIC_CHAT) && !name.equalsIgnoreCase(username))
                .filter(name -> presenceByUser.get(name) == PresenceStatus.ONLINE)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        if (onlineList.isEmpty()) {
            Label empty = new Label("No users online");
            empty.getStyleClass().add("muted-label");
            empty.setStyle("-fx-font-size: 11px; -fx-padding: 6px 4px;");
            onlineUsersBox.getChildren().add(empty);
            return;
        }

        for (String user : onlineList) {
            VBox item = new VBox(3);
            item.getStyleClass().add("online-user-bubble");
            item.setAlignment(Pos.CENTER);

            StackPane avatar = new StackPane();
            avatar.getStyleClass().add("online-bubble-avatar");
            Label initLabel = new Label(initials(user));
            initLabel.getStyleClass().add("contact-avatar-text");
            initLabel.setStyle("-fx-font-size: 11px;");

            Region dot = new Region();
            dot.getStyleClass().add("online-indicator-dot");
            StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
            avatar.getChildren().addAll(initLabel, dot);

            Label nameLabel = new Label(user.length() > 8 ? user.substring(0, 7) + "…" : user);
            nameLabel.getStyleClass().add("online-bubble-name");

            item.getChildren().addAll(avatar, nameLabel);
            item.setOnMouseClicked(e -> {
                switchToChatsTab();
                contactList.getSelectionModel().select(user);
                updateConversationSelection(user);
            });

            onlineUsersBox.getChildren().add(item);
        }
    }

    private void switchToChatsTab() {
        viewingGroups = false;
        tabChatsButton.getStyleClass().removeAll("nav-tab-button-active");
        tabChatsButton.getStyleClass().add("nav-tab-button-active");
        tabGroupsButton.getStyleClass().removeAll("nav-tab-button-active");

        contactList.setVisible(true);
        contactList.setManaged(true);
        groupListView.setVisible(false);
        groupListView.setManaged(false);

        if (onlineUsersSection != null) {
            onlineUsersSection.setVisible(true);
            onlineUsersSection.setManaged(true);
        }

        searchField.setPromptText("Search people...");
        searchField.clear();
    }

    private void switchToGroupsTab() {
        viewingGroups = true;
        tabGroupsButton.getStyleClass().removeAll("nav-tab-button-active");
        tabGroupsButton.getStyleClass().add("nav-tab-button-active");
        tabChatsButton.getStyleClass().removeAll("nav-tab-button-active");

        groupListView.setVisible(true);
        groupListView.setManaged(true);
        contactList.setVisible(false);
        contactList.setManaged(false);

        if (onlineUsersSection != null) {
            onlineUsersSection.setVisible(false);
            onlineUsersSection.setManaged(false);
        }

        searchField.setPromptText("Search groups...");
        searchField.clear();

        if (client != null && client.isConnected()) {
            try {
                client.requestGroupList();
            } catch (IOException ignored) {
            }
        }
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
        panel.setPrefWidth(285);
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

        // Media box for direct chats
        mediaBox = new VBox(10);
        mediaBox.getStyleClass().add("media-placeholder");
        Label mediaHeading = new Label("Media");
        mediaHeading.getStyleClass().add("section-heading");
        Label mediaText = new Label(
                "Image and file sharing will appear here in a later phase."
        );
        mediaText.getStyleClass().add("muted-label");
        mediaText.setWrapText(true);
        mediaBox.getChildren().addAll(mediaHeading, mediaText);

        // Group details box
        groupDetailsBox = new VBox(12);
        groupDetailsBox.setAlignment(Pos.TOP_LEFT);

        groupIdLabel = new Label();
        groupIdLabel.getStyleClass().add("field-label");
        groupIdLabel.setWrapText(true);

        membersHeading = new Label("Members (0)");
        membersHeading.getStyleClass().add("section-heading");

        addMemberButton = new Button("+ Add Member");
        addMemberButton.getStyleClass().add("action-secondary-btn");
        addMemberButton.setMaxWidth(Double.MAX_VALUE);
        addMemberButton.setOnAction(event -> {
            if (selectedGroup != null) {
                showAddMemberDialog(selectedGroup);
            }
        });

        groupMembersBox = new VBox(6);
        ScrollPane membersScroll = new ScrollPane(groupMembersBox);
        membersScroll.setFitToWidth(true);
        membersScroll.setPrefHeight(180);
        membersScroll.getStyleClass().add("message-scroll");

        Button leaveGroupBtn = new Button("Leave Group");
        leaveGroupBtn.getStyleClass().add("danger-button");
        leaveGroupBtn.setMaxWidth(Double.MAX_VALUE);
        leaveGroupBtn.setOnAction(event -> confirmLeaveGroup());

        groupDetailsBox.getChildren().addAll(
                groupIdLabel,
                membersHeading,
                addMemberButton,
                membersScroll,
                leaveGroupBtn
        );
        groupDetailsBox.setVisible(false);
        groupDetailsBox.setManaged(false);

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
                mediaBox,
                groupDetailsBox,
                spacer,
                chatStatus,
                logout
        );
        return panel;
    }

    private void refreshGroupMembersDisplay() {
        if (groupMembersBox == null) {
            return;
        }
        groupMembersBox.getChildren().clear();
        membersHeading.setText("Members (" + currentGroupMembers.size() + ")");

        boolean isCurrentUserAdmin = currentGroupMembers.stream().anyMatch(
                m -> m.username().equalsIgnoreCase(username) && m.role() == GroupMemberRole.ADMIN
        );

        addMemberButton.setVisible(isCurrentUserAdmin);
        addMemberButton.setManaged(isCurrentUserAdmin);

        for (GroupMember member : currentGroupMembers) {
            HBox row = new HBox(8);
            row.getStyleClass().add("group-member-row");
            row.setAlignment(Pos.CENTER_LEFT);

            Region dot = new Region();
            boolean isOnline = member.username().equalsIgnoreCase(username)
                    || presenceByUser.get(member.username()) == PresenceStatus.ONLINE;
            dot.getStyleClass().add(isOnline ? "online-indicator-dot" : "offline-indicator-dot");

            Label nameLabel = new Label(member.username());
            nameLabel.getStyleClass().add("contact-name");
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            Label roleBadge = new Label(member.role().name());
            roleBadge.getStyleClass().add(
                    member.role() == GroupMemberRole.ADMIN ? "admin-badge" : "member-badge"
            );

            row.getChildren().addAll(dot, nameLabel, roleBadge);

            if (isCurrentUserAdmin && !member.username().equalsIgnoreCase(username)) {
                Button removeBtn = new Button("✕");
                removeBtn.getStyleClass().add("remove-member-btn");
                removeBtn.setOnAction(e -> confirmRemoveMember(member.username()));
                row.getChildren().add(removeBtn);
            }

            groupMembersBox.getChildren().add(row);
        }
    }

    private void showCreateGroupDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(stage);
        dialog.setTitle("Create New Group");

        VBox card = new VBox(14);
        card.getStyleClass().add("login-card");
        card.setPadding(new Insets(24));
        card.setPrefWidth(420);

        Label heading = new Label("Create New Group");
        heading.getStyleClass().add("section-heading");
        Label subtitle = new Label("Set up a new discussion group and add members.");
        subtitle.getStyleClass().add("muted-label");

        TextField nameField = new TextField();
        nameField.setPromptText("Group Name");
        nameField.getStyleClass().add("text-field");

        TextField descField = new TextField();
        descField.setPromptText("Description (optional)");
        descField.getStyleClass().add("text-field");

        Label membersLabel = new Label("Select initial members (optional):");
        membersLabel.getStyleClass().add("field-label");

        List<String> availableUsers = contacts.stream()
                .filter(c -> !c.equals(PUBLIC_CHAT) && !c.equalsIgnoreCase(username))
                .toList();
        ListView<String> membersSelection = new ListView<>(
                FXCollections.observableArrayList(availableUsers)
        );
        membersSelection.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        membersSelection.setPrefHeight(120);
        membersSelection.getStyleClass().add("contact-list");

        Label status = new Label();
        status.getStyleClass().add("login-status");

        Button createBtn = new Button("Create Group");
        createBtn.getStyleClass().add("primary-button");
        createBtn.setMaxWidth(Double.MAX_VALUE);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("action-secondary-btn");
        cancelBtn.setMaxWidth(Double.MAX_VALUE);
        cancelBtn.setOnAction(e -> dialog.close());

        createBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isBlank()) {
                status.setText("Group name must not be blank.");
                return;
            }
            List<String> initialMembers = new ArrayList<>(
                    membersSelection.getSelectionModel().getSelectedItems()
            );
            try {
                client.createGroup(name, descField.getText().trim(), initialMembers);
                dialog.close();
                switchToGroupsTab();
                setChatStatus("Creating group " + name + "...");
            } catch (IOException ex) {
                status.setText("Error creating group: " + ex.getMessage());
            }
        });

        HBox buttons = new HBox(10, cancelBtn, createBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(
                heading,
                subtitle,
                labelledField("GROUP NAME", nameField),
                labelledField("DESCRIPTION", descField),
                membersLabel,
                membersSelection,
                status,
                buttons
        );

        Scene scene = new Scene(card);
        applyStyles(scene);
        dialog.setScene(scene);
        dialog.show();
    }

    private void showAddMemberDialog(ChatGroup group) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(stage);
        dialog.setTitle("Add Member");

        VBox card = new VBox(14);
        card.getStyleClass().add("login-card");
        card.setPadding(new Insets(24));
        card.setPrefWidth(380);

        Label heading = new Label("Add Member to " + group.name());
        heading.getStyleClass().add("section-heading");

        TextField usernameField = new TextField();
        usernameField.setPromptText("Enter username to add");
        usernameField.getStyleClass().add("text-field");

        Label status = new Label();
        status.getStyleClass().add("login-status");

        Set<String> existingMemberNames = new HashSet<>();
        for (GroupMember m : currentGroupMembers) {
            existingMemberNames.add(m.username().toLowerCase(Locale.ROOT));
        }

        List<String> candidates = contacts.stream()
                .filter(c -> !c.equals(PUBLIC_CHAT) && !existingMemberNames.contains(c.toLowerCase(Locale.ROOT)))
                .toList();

        ListView<String> candidateList = new ListView<>(FXCollections.observableArrayList(candidates));
        candidateList.setPrefHeight(100);
        candidateList.getStyleClass().add("contact-list");
        candidateList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, selected) -> {
                    if (selected != null) {
                        usernameField.setText(selected);
                    }
                }
        );

        Button addBtn = new Button("Add Member");
        addBtn.getStyleClass().add("primary-button");
        addBtn.setMaxWidth(Double.MAX_VALUE);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("action-secondary-btn");
        cancelBtn.setMaxWidth(Double.MAX_VALUE);
        cancelBtn.setOnAction(e -> dialog.close());

        addBtn.setOnAction(e -> {
            String targetUser = usernameField.getText().trim();
            if (targetUser.isBlank()) {
                status.setText("Username must not be blank.");
                return;
            }
            try {
                client.addGroupMember(group.groupId(), targetUser);
                dialog.close();
                setChatStatus("Adding " + targetUser + " to " + group.name() + "...");
            } catch (IOException ex) {
                status.setText("Error adding member: " + ex.getMessage());
            }
        });

        HBox buttons = new HBox(10, cancelBtn, addBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(
                heading,
                labelledField("USERNAME", usernameField),
                new Label("Or select from contacts:"),
                candidateList,
                status,
                buttons
        );

        Scene scene = new Scene(card);
        applyStyles(scene);
        dialog.setScene(scene);
        dialog.show();
    }

    private void confirmRemoveMember(String memberUsername) {
        if (selectedGroup == null) {
            return;
        }
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Are you sure you want to remove " + memberUsername + " from " + selectedGroup.name() + "?",
                ButtonType.YES,
                ButtonType.NO
        );
        alert.setHeaderText("Remove Member");
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                try {
                    client.removeGroupMember(selectedGroup.groupId(), memberUsername);
                    setChatStatus("Removing " + memberUsername + "...");
                } catch (IOException ex) {
                    setChatStatus("Could not remove member: " + ex.getMessage());
                }
            }
        });
    }

    private void confirmLeaveGroup() {
        if (selectedGroup == null) {
            return;
        }
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Are you sure you want to leave " + selectedGroup.name() + "?",
                ButtonType.YES,
                ButtonType.NO
        );
        alert.setHeaderText("Leave Group");
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                try {
                    String leavingId = selectedGroup.groupId();
                    client.leaveGroup(leavingId);
                    userGroups.removeIf(g -> g.groupId().equals(leavingId));
                    updateGroupTabBadge();
                    updateConversationSelection(PUBLIC_CHAT);
                    switchToChatsTab();
                    setChatStatus("Left group successfully.");
                } catch (IOException ex) {
                    setChatStatus("Could not leave group: " + ex.getMessage());
                }
            }
        });
    }

    private void sendCurrentMessage() {
        String text = messageInput.getText();
        if (text == null || text.isBlank()) {
            setChatStatus("Message cannot be blank.");
            return;
        }

        try {
            long now = System.currentTimeMillis();
            if (selectedGroup != null) {
                client.sendGroupMessage(selectedGroup.groupId(), text);
                latestMessagePreviews.put(selectedGroup.groupId(), username + ": " + text);
                latestMessageTimestamps.put(selectedGroup.groupId(), now);
                if (groupListView != null) groupListView.refresh();
            } else if (selectedConversation.equals(PUBLIC_CHAT)) {
                client.sendPublicMessage(text);
            } else {
                client.sendPrivateMessage(selectedConversation, text);
                latestMessagePreviews.put(selectedConversation, text);
                latestMessageTimestamps.put(selectedConversation, now);
                if (contactList != null) contactList.refresh();
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
            case CONVERSATION_SUMMARIES_RESPONSE -> handleConversationSummariesResponse(message);
            case READ_STATUS_UPDATE -> {
                String conv = message.target();
                if (conv != null && !conv.isBlank()) {
                    unreadCounts.put(conv, 0);
                    if (contactList != null) contactList.refresh();
                    if (groupListView != null) groupListView.refresh();
                }
            }
            case USER_JOINED -> {
                if (!message.payload().equalsIgnoreCase(username)) {
                    presenceByUser.put(message.payload(), PresenceStatus.ONLINE);
                    addContact(message.payload());
                    refreshPresenceDisplay();
                    storeAndDisplay(PUBLIC_CHAT, message);
                }
            }
            case USER_LEFT -> {
                presenceByUser.put(message.payload(), PresenceStatus.OFFLINE);
                refreshPresenceDisplay();
                storeAndDisplay(PUBLIC_CHAT, message);
            }
            case PUBLIC_MESSAGE -> storeAndDisplay(PUBLIC_CHAT, message);
            case PRIVATE_MESSAGE -> {
                String conversation = username.equals(message.sender())
                        ? message.target()
                        : message.sender();
                addContact(conversation);
                storeAndDisplay(conversation, message);

                latestMessagePreviews.put(conversation, message.payload());
                latestMessageTimestamps.put(conversation, message.timestamp());

                if (!message.sender().equalsIgnoreCase(username)) {
                    if (conversation.equalsIgnoreCase(selectedConversation)) {
                        unreadCounts.put(conversation, 0);
                        if (client != null && client.isConnected()) {
                            try {
                                client.markConversationRead(conversation, false, 0);
                            } catch (IOException ignored) {}
                        }
                    } else {
                        unreadCounts.merge(conversation, 1, Integer::sum);
                        notificationManager.notifyMessage(
                                message.sender(),
                                message.payload(),
                                null,
                                conversation,
                                false,
                                message.timestamp(),
                                message.requestId()
                        );
                    }
                }
                if (contactList != null) {
                    contactList.refresh();
                }
            }
            case HISTORY_RESPONSE -> handleHistoryResponse(message);
            case GROUP_LIST_RESPONSE -> handleGroupListResponse(message);
            case GROUP_CREATE_SUCCESS -> handleGroupCreateSuccess(message);
            case GROUP_CREATE_FAILURE -> {
                setChatStatus(message.payload());
                Alert alert = new Alert(Alert.AlertType.ERROR, message.payload(), ButtonType.OK);
                alert.setHeaderText("Group Creation Failed");
                alert.show();
            }
            case GROUP_MEMBERS_RESPONSE -> handleGroupMembersResponse(message);
            case GROUP_OPERATION_SUCCESS -> handleGroupOperationSuccess(message);
            case GROUP_MESSAGE -> handleGroupChatMessage(message);
            case GROUP_HISTORY_RESPONSE -> handleGroupHistoryResponse(message);
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

    private void handleGroupListResponse(ChatMessage message) {
        try {
            List<ChatGroup> list = objectMapper.readValue(
                    message.payload(),
                    new TypeReference<List<ChatGroup>>() {}
            );
            userGroups.setAll(list);
            updateGroupTabBadge();
            if (groupListView != null) {
                groupListView.refresh();
            }
        } catch (Exception exception) {
            setChatStatus("Could not parse group list: " + exception.getMessage());
        }
    }

    private void handleGroupCreateSuccess(ChatMessage message) {
        try {
            ChatGroup group = objectMapper.readValue(message.payload(), ChatGroup.class);
            boolean exists = userGroups.stream().anyMatch(g -> g.groupId().equals(group.groupId()));
            if (!exists) {
                userGroups.add(group);
            }
            updateGroupTabBadge();
            switchToGroupsTab();
            selectGroup(group);
            setChatStatus("Group '" + group.name() + "' created successfully!");
        } catch (Exception exception) {
            setChatStatus("Could not process group creation: " + exception.getMessage());
        }
    }

    private void handleGroupMembersResponse(ChatMessage message) {
        if (selectedGroup != null && selectedGroup.groupId().equals(message.target())) {
            try {
                List<GroupMember> members = objectMapper.readValue(
                        message.payload(),
                        new TypeReference<List<GroupMember>>() {}
                );
                currentGroupMembers.setAll(members);
                refreshGroupMembersDisplay();
            } catch (Exception exception) {
                setChatStatus("Could not parse members: " + exception.getMessage());
            }
        }
    }

    private void handleGroupOperationSuccess(ChatMessage message) {
        setChatStatus(message.payload());
        if (selectedGroup != null && selectedGroup.groupId().equals(message.target())) {
            try {
                client.requestGroupMembers(selectedGroup.groupId());
            } catch (IOException ignored) {
            }
        }
        if (client != null && client.isConnected()) {
            try {
                client.requestGroupList();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleGroupChatMessage(ChatMessage message) {
        String groupId = message.target();
        if (groupId == null || groupId.isBlank()) {
            return;
        }

        storeAndDisplay(groupId, message);

        latestMessagePreviews.put(groupId, message.sender() + ": " + message.payload());
        latestMessageTimestamps.put(groupId, message.timestamp());

        if (!message.sender().equalsIgnoreCase(username)) {
            if (groupId.equalsIgnoreCase(selectedConversation)) {
                unreadCounts.put(groupId, 0);
                if (client != null && client.isConnected()) {
                    try {
                        client.markConversationRead(groupId, true, 0);
                    } catch (IOException ignored) {}
                }
            } else {
                unreadCounts.merge(groupId, 1, Integer::sum);
                ChatGroup group = userGroups.stream()
                        .filter(g -> g.groupId().equals(groupId))
                        .findFirst()
                        .orElse(null);
                String groupName = group != null ? group.name() : groupId;
                notificationManager.notifyMessage(
                        message.sender(),
                        message.payload(),
                        groupName,
                        groupId,
                        true,
                        message.timestamp(),
                        message.requestId()
                );
            }
        }
        if (groupListView != null) {
            groupListView.refresh();
        }
    }

    private void handleConversationSummariesResponse(ChatMessage message) {
        try {
            List<ConversationSummary> summaries = objectMapper.readValue(
                    message.payload(),
                    new TypeReference<List<ConversationSummary>>() {}
            );
            for (ConversationSummary summary : summaries) {
                if (summary.lastMessage() != null && !summary.lastMessage().isBlank()) {
                    String preview = summary.isGroup() && !summary.lastSender().isBlank()
                            ? summary.lastSender() + ": " + summary.lastMessage()
                            : summary.lastMessage();
                    latestMessagePreviews.put(summary.conversationId(), preview);
                }
                if (summary.lastTimestamp() > 0) {
                    latestMessageTimestamps.put(summary.conversationId(), summary.lastTimestamp());
                }
                if (!summary.conversationId().equalsIgnoreCase(selectedConversation)) {
                    unreadCounts.put(summary.conversationId(), summary.unreadCount());
                } else {
                    unreadCounts.put(summary.conversationId(), 0);
                }
            }
            if (contactList != null) {
                contactList.refresh();
            }
            if (groupListView != null) {
                groupListView.refresh();
            }
        } catch (Exception exception) {
            setChatStatus("Could not parse conversation summaries: " + exception.getMessage());
        }
    }

    private void handleGroupHistoryResponse(ChatMessage message) {
        String groupId = message.target();
        if (groupId == null || groupId.isBlank()) {
            return;
        }

        try {
            List<StoredGroupMessage> history = objectMapper.readValue(
                    message.payload(),
                    new TypeReference<List<StoredGroupMessage>>() {}
            );
            List<ChatMessage> chatMessages = conversations.computeIfAbsent(
                    groupId,
                    key -> new ArrayList<>()
            );

            boolean anyAdded = false;
            for (StoredGroupMessage stored : history) {
                ChatMessage reconstructed = new ChatMessage(
                        MessageType.GROUP_MESSAGE,
                        stored.sender(),
                        stored.groupId(),
                        stored.content(),
                        stored.sentAt(),
                        stored.requestId()
                );
                boolean exists = chatMessages.stream()
                        .anyMatch(existing -> existing.requestId().equals(reconstructed.requestId()));
                if (!exists) {
                    chatMessages.add(reconstructed);
                    anyAdded = true;
                }
            }

            if (anyAdded) {
                chatMessages.sort(Comparator.comparingLong(ChatMessage::timestamp));
                if (groupId.equals(selectedConversation)) {
                    refreshMessages();
                }
            }
        } catch (Exception exception) {
            setChatStatus("Could not parse group history: " + exception.getMessage());
        }
    }

    private void updateGroupTabBadge() {
        if (tabGroupsButton != null) {
            tabGroupsButton.setText("Groups (" + userGroups.size() + ")");
        }
    }

    private void updateContacts(String commaSeparatedUsers) {
        List<String> names = new ArrayList<>();
        if (commaSeparatedUsers != null && !commaSeparatedUsers.isBlank()) {
            for (String name : commaSeparatedUsers.split(",")) {
                if (!name.equalsIgnoreCase(username) && !name.isBlank()) {
                    names.add(name);
                    presenceByUser.putIfAbsent(name, PresenceStatus.OFFLINE);
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        contacts.setAll(PUBLIC_CHAT);
        contacts.addAll(names);
        refreshPresenceDisplay();
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
                .sorted((u1, u2) -> {
                    boolean online1 = presenceByUser.get(u1) == PresenceStatus.ONLINE;
                    boolean online2 = presenceByUser.get(u2) == PresenceStatus.ONLINE;
                    if (online1 != online2) {
                        return online1 ? -1 : 1;
                    }
                    return u1.compareToIgnoreCase(u2);
                })
                .toList();
        List<String> targetList = new ArrayList<>();
        targetList.add(PUBLIC_CHAT);
        targetList.addAll(users);

        if (!targetList.equals(contacts)) {
            contacts.setAll(targetList);
            selectCurrentContact();
        }
    }

    private void selectCurrentContact() {
        if (contactList != null && selectedGroup == null) {
            contactList.getSelectionModel().select(selectedConversation);
        }
    }

    private void selectGroup(ChatGroup group) {
        selectedGroup = group;
        selectedConversation = group.groupId();
        notificationManager.setActiveConversationId(group.groupId());
        unreadCounts.put(group.groupId(), 0);
        if (groupListView != null) {
            groupListView.refresh();
        }
        if (conversationTitle != null) {
            conversationTitle.setText(group.name());
            conversationSubtitle.setText(group.description().isBlank()
                    ? "Group chat • Created by " + group.creatorUsername()
                    : group.description());
            profileName.setText(group.name());
            profileInitials.setText(initials(group.name()));
            profilePresence.setText("Group conversation");

            groupIdLabel.setText("ID: " + group.groupId());

            mediaBox.setVisible(false);
            mediaBox.setManaged(false);
            groupDetailsBox.setVisible(true);
            groupDetailsBox.setManaged(true);

            currentGroupMembers.clear();
            refreshGroupMembersDisplay();

            groupHistoryOffsets.put(group.groupId(), 0);
            refreshMessages();

            if (client != null && client.isConnected()) {
                try {
                    client.requestGroupMembers(group.groupId());
                    client.requestGroupHistory(group.groupId(), 50, 0);
                    client.markConversationRead(group.groupId(), true, 0);
                } catch (IOException exception) {
                    setChatStatus("Could not fetch group details: " + exception.getMessage());
                }
            }
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
        selectedGroup = null;
        selectedConversation = conversation;
        notificationManager.setActiveConversationId(conversation);
        unreadCounts.put(conversation, 0);
        if (contactList != null) {
            contactList.refresh();
        }
        if (conversationTitle != null) {
            if (mediaBox != null) {
                mediaBox.setVisible(true);
                mediaBox.setManaged(true);
            }
            if (groupDetailsBox != null) {
                groupDetailsBox.setVisible(false);
                groupDetailsBox.setManaged(false);
            }
            conversationTitle.setText(conversation);
            conversationSubtitle.setText(conversation.equals(PUBLIC_CHAT)
                    ? "Messages visible to everyone online"
                    : "Private conversation");
            profileName.setText(conversation);
            profileInitials.setText(initials(conversation));
            updateProfilePresence();
            refreshMessages();
            selectCurrentContact();

            if (client != null && client.isConnected()) {
                try {
                    client.requestHistory(conversation.equals(PUBLIC_CHAT) ? null : conversation, 50);
                } catch (IOException exception) {
                    setChatStatus("Could not fetch history: " + exception.getMessage());
                }
                if (!conversation.equals(PUBLIC_CHAT)) {
                    try {
                        client.markConversationRead(conversation, false, 0);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void handleHistoryResponse(ChatMessage message) {
        String target = message.target();
        String conversationKey = (target == null || target.isBlank() || target.equalsIgnoreCase("Public Chat"))
                ? PUBLIC_CHAT
                : target;

        try {
            List<StoredMessage> history = objectMapper.readValue(
                    message.payload(),
                    new TypeReference<List<StoredMessage>>() {}
            );
            List<ChatMessage> chatMessages = conversations.computeIfAbsent(
                    conversationKey,
                    key -> new ArrayList<>()
            );

            boolean anyAdded = false;
            for (StoredMessage stored : history) {
                MessageType type = MessageType.valueOf(stored.messageType());
                ChatMessage reconstructed = new ChatMessage(
                        type,
                        stored.sender(),
                        stored.recipient(),
                        stored.content(),
                        stored.sentAt(),
                        stored.requestId()
                );
                boolean exists = chatMessages.stream()
                        .anyMatch(existing -> existing.requestId().equals(reconstructed.requestId()));
                if (!exists) {
                    chatMessages.add(reconstructed);
                    anyAdded = true;
                }
            }

            if (anyAdded) {
                chatMessages.sort(Comparator.comparingLong(ChatMessage::timestamp));
                if (conversationKey.equalsIgnoreCase(selectedConversation)) {
                    refreshMessages();
                }
            }
        } catch (Exception exception) {
            setChatStatus("Could not parse conversation history: " + exception.getMessage());
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
        sortContacts();
        refreshOnlineUsersStrip();
        if (contactList != null) {
            contactList.refresh();
        }
        if (selectedGroup != null) {
            refreshGroupMembersDisplay();
        }
        updateProfilePresence();
    }

    private void updateProfilePresence() {
        if (profilePresence == null) {
            return;
        }
        if (selectedGroup != null) {
            profilePresence.setText("Group conversation");
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
                        PresenceStatus.OFFLINE
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
                ? PresenceStatus.OFFLINE
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

        if (selectedGroup != null) {
            String groupId = selectedGroup.groupId();
            Button loadEarlier = new Button("⬆ Load earlier messages");
            loadEarlier.getStyleClass().add("pagination-button");
            loadEarlier.setMaxWidth(Double.MAX_VALUE);
            loadEarlier.setOnAction(e -> {
                int currentOffset = groupHistoryOffsets.getOrDefault(groupId, 0) + 50;
                groupHistoryOffsets.put(groupId, currentOffset);
                try {
                    client.requestGroupHistory(groupId, 50, currentOffset);
                    setChatStatus("Loading earlier messages...");
                } catch (IOException ex) {
                    setChatStatus("Could not fetch earlier messages: " + ex.getMessage());
                }
            });
            HBox row = new HBox(loadEarlier);
            row.setAlignment(Pos.CENTER);
            messagesBox.getChildren().add(row);
        }

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

    private static String formatTimestamp(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return "";
        }
        LocalDate messageDate = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        LocalDate today = LocalDate.now();
        if (messageDate.isEqual(today)) {
            return DateTimeFormatter.ofPattern("hh:mm a").format(
                    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            );
        } else if (messageDate.isEqual(today.minusDays(1))) {
            return "Yesterday";
        } else {
            return DateTimeFormatter.ofPattern("MMM d").format(
                    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            );
        }
    }

    private static String truncateText(String text, int maxLength) {
        if (text == null) return "";
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() > maxLength
                ? singleLine.substring(0, maxLength - 1) + "…"
                : singleLine;
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

            // Avatar
            StackPane avatar = new StackPane();
            avatar.getStyleClass().add("contact-avatar");
            Label initials = new Label(name.equals(PUBLIC_CHAT) ? "#" : initials(name));
            initials.getStyleClass().add("contact-avatar-text");
            avatar.getChildren().add(initials);

            if (!name.equals(PUBLIC_CHAT)) {
                Region onlineDot = new Region();
                boolean isOnline = presenceByUser.get(name) == PresenceStatus.ONLINE;
                onlineDot.getStyleClass().add(isOnline ? "online-indicator-dot" : "offline-indicator-dot");
                StackPane.setAlignment(onlineDot, Pos.BOTTOM_RIGHT);
                avatar.getChildren().add(onlineDot);
            }

            // Top line: Name + Timestamp
            Label title = new Label(name);
            title.getStyleClass().add("contact-name");
            HBox.setHgrow(title, Priority.ALWAYS);

            Label timeLabel = new Label();
            timeLabel.getStyleClass().add("cell-time-text");
            Long ts = latestMessageTimestamps.get(name);
            if (ts != null && ts > 0) {
                timeLabel.setText(formatTimestamp(ts));
            }

            HBox topLine = new HBox(6, title, timeLabel);
            topLine.setAlignment(Pos.CENTER_LEFT);

            // Bottom line: Preview snippet + Unread Badge
            String previewText = latestMessagePreviews.get(name);
            if (previewText == null || previewText.isBlank()) {
                if (name.equals(PUBLIC_CHAT)) {
                    previewText = "Public room for everyone";
                } else {
                    boolean isOnline = presenceByUser.get(name) == PresenceStatus.ONLINE;
                    previewText = isOnline ? "Online" : "Offline";
                }
            }

            Label previewLabel = new Label(truncateText(previewText, 24));
            previewLabel.getStyleClass().add("cell-preview-text");
            HBox.setHgrow(previewLabel, Priority.ALWAYS);

            int unread = unreadCounts.getOrDefault(name, 0);
            HBox bottomLine = new HBox(6, previewLabel);
            bottomLine.setAlignment(Pos.CENTER_LEFT);

            if (unread > 0) {
                Label badge = new Label(String.valueOf(unread));
                badge.getStyleClass().add("unread-badge");
                bottomLine.getChildren().add(badge);
            }

            VBox labels = new VBox(3, topLine, bottomLine);
            HBox.setHgrow(labels, Priority.ALWAYS);

            HBox graphic = new HBox(12, avatar, labels);
            graphic.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(graphic);
        }
    }

    private final class GroupCell extends ListCell<ChatGroup> {
        @Override
        protected void updateItem(ChatGroup group, boolean empty) {
            super.updateItem(group, empty);
            if (empty || group == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            StackPane avatar = new StackPane();
            avatar.getStyleClass().add("contact-avatar");
            Label initials = new Label(initials(group.name()));
            initials.getStyleClass().add("contact-avatar-text");
            avatar.getChildren().add(initials);

            Label title = new Label(group.name());
            title.getStyleClass().add("contact-name");
            HBox.setHgrow(title, Priority.ALWAYS);

            Label timeLabel = new Label();
            timeLabel.getStyleClass().add("cell-time-text");
            Long ts = latestMessageTimestamps.get(group.groupId());
            if (ts != null && ts > 0) {
                timeLabel.setText(formatTimestamp(ts));
            }

            HBox topLine = new HBox(6, title, timeLabel);
            topLine.setAlignment(Pos.CENTER_LEFT);

            String previewText = latestMessagePreviews.get(group.groupId());
            if (previewText == null || previewText.isBlank()) {
                previewText = group.description().isBlank()
                        ? "Created by " + group.creatorUsername()
                        : group.description();
            }

            Label previewLabel = new Label(truncateText(previewText, 22));
            previewLabel.getStyleClass().add("cell-preview-text");
            HBox.setHgrow(previewLabel, Priority.ALWAYS);

            int unread = unreadCounts.getOrDefault(group.groupId(), 0);
            HBox bottomLine = new HBox(6, previewLabel);
            bottomLine.setAlignment(Pos.CENTER_LEFT);

            if (group.creatorUsername().equalsIgnoreCase(username)) {
                Label adminPill = new Label("ADMIN");
                adminPill.getStyleClass().add("admin-badge");
                bottomLine.getChildren().add(adminPill);
            }

            if (unread > 0) {
                Label badge = new Label(String.valueOf(unread));
                badge.getStyleClass().add("unread-badge");
                bottomLine.getChildren().add(badge);
            }

            VBox labels = new VBox(3, topLine, bottomLine);
            HBox.setHgrow(labels, Priority.ALWAYS);

            HBox graphic = new HBox(12, avatar, labels);
            graphic.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(graphic);
        }
    }
}
