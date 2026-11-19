
package com.securechat.database;

public class DatabaseTest {

    public static void main(String[] args) {

        try {
            DatabaseManager.initializeDatabase();

            UserRepository repository = new UserRepository();

            char[] password = "SecureChat123".toCharArray();

            boolean registered = repository.registerUser(
                    "testuser", password
            );

            System.out.println("Registration successful: " + registered);

            boolean login = repository.authenticateUser(
                    "testuser", password
            );

            System.out.println("Login successful: " + login);

            boolean wrongPassword = repository.authenticateUser(
                    "testuser", "WrongPassword123".toCharArray()
            );

            System.out.println("Wrong password accepted: " + wrongPassword);

            java.util.Arrays.fill(password, '\0');

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
