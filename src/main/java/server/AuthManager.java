package server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe authentication manager.
 * Manages user credentials and login sessions using ReentrantLock.
 */
public class AuthManager {
    private final Map<String, String> userCredentials;
    private final Set<String> loggedInUsers;
    private final ReentrantLock lock;

    public AuthManager() {
        this.userCredentials = new HashMap<>();
        this.loggedInUsers = new HashSet<>();
        this.lock = new ReentrantLock();
    }

    /**
     * Add a new user with username and password.
     */
    public void addUser(String username, String password) {
        lock.lock();
        try {
            userCredentials.put(username, password);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if a user already exists.
     */
    public boolean userExists(String username) {
        lock.lock();
        try {
            return userCredentials.containsKey(username);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Authenticate a user with username and password.
     * If successful, marks the user as logged in.
     *
     * @return true if authentication succeeded, false otherwise
     */
    public boolean authenticate(String username, String password) {
        lock.lock();
        try {
            if (userCredentials.containsKey(username) && userCredentials.get(username).equals(password)) {
                loggedInUsers.add(username);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Logout a user.
     */
    public void logout(String username) {
        lock.lock();
        try {
            loggedInUsers.remove(username);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if a user is currently logged in.
     */
    public boolean isLoggedIn(String username) {
        lock.lock();
        try {
            return loggedInUsers.contains(username);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the number of currently logged in users.
     */
    public int getLoggedInCount() {
        lock.lock();
        try {
            return loggedInUsers.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "AuthManager{users=" + userCredentials.size() + ", loggedIn=" + loggedInUsers.size() + "}";
        } finally {
            lock.unlock();
        }
    }
}
