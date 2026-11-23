package ohi.andre.consolelauncher.managers.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * The open conversation, kept only in memory (it disappears when the app process dies).
 * The API keeps no state, so every request resends these messages.
 * It ends when the user types "ai -fim" / "ai -novo", or after INACTIVITY_MS without use.
 */
public class AiConversation {

    public static final long INACTIVITY_MS = 30L * 60L * 1000L;

    public static class Message {
        public final String role; // "user" or "assistant"
        public final String text;

        Message(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private static final List<Message> messages = new ArrayList<>();
    private static long lastActivity;
    private static long billedIn, billedOut;   // tokens spent by this conversation so far
    private static long lastInput;             // input tokens of the last request ~ current size of the context
    private static boolean sizeWarned;

    public static synchronized boolean isOpen() {
        return !messages.isEmpty();
    }

    /** True when there is a conversation and it has been idle for more than INACTIVITY_MS. */
    public static synchronized boolean expired(long now) {
        return isOpen() && now - lastActivity > INACTIVITY_MS;
    }

    public static synchronized void clear() {
        messages.clear();
        lastActivity = 0;
        billedIn = billedOut = lastInput = 0;
        sizeWarned = false;
    }

    /** Copy of the history, to be sent together with the new user message. */
    public static synchronized List<Message> snapshot() {
        return new ArrayList<>(messages);
    }

    public static synchronized int size() {
        return messages.size();
    }

    /** Only called after a successful exchange, so a failed request never pollutes the history. */
    public static synchronized void addExchange(String user, String assistant, long inTokens, long outTokens, long now) {
        messages.add(new Message("user", user));
        messages.add(new Message("assistant", assistant));
        billedIn += inTokens;
        billedOut += outTokens;
        lastInput = inTokens;
        lastActivity = now;
    }

    public static synchronized long contextTokens() {
        return lastInput;
    }

    public static synchronized long billedIn() {
        return billedIn;
    }

    public static synchronized long billedOut() {
        return billedOut;
    }

    /** True once per conversation, the first time the context passes "limit" tokens. */
    public static synchronized boolean shouldWarnSize(long limit) {
        if (limit <= 0 || sizeWarned || lastInput < limit) return false;
        sizeWarned = true;
        return true;
    }
}
