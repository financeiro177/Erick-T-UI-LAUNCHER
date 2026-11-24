package ohi.andre.consolelauncher.managers.reminder;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.List;

/** The lines shown above the notes at the top of the home screen: the next reminders, overdue ones first. */
public class ReminderSummary {

    private static final int GREEN = 0xFF39D353;
    private static final int YELLOW = 0xFFFFD54F;
    private static final int MAX_LINES = 3;
    private static final int MAX_TITLE = 38;

    /** Empty when there are no reminders. */
    public static CharSequence text(Context c) {
        List<ReminderStore.Reminder> list = ReminderStore.all(c);
        if (list.isEmpty()) return "";

        long now = System.currentTimeMillis();
        SpannableStringBuilder sb = new SpannableStringBuilder();

        int lines = 0;
        for (ReminderStore.Reminder r : list) {
            if (lines == MAX_LINES) break;
            lines++;

            boolean late = r.when <= now;
            String title = r.title.length() > MAX_TITLE ? r.title.substring(0, MAX_TITLE - 1) + "…" : r.title;
            String line = (late ? "! pendente " + ReminderStore.hour(r.when) : "» " + ReminderStore.label(r.when)) + "  " + title + "\n";

            int start = sb.length();
            sb.append(line);
            sb.setSpan(new ForegroundColorSpan(late ? YELLOW : GREEN), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (list.size() > MAX_LINES) {
            int start = sb.length();
            sb.append("+").append(String.valueOf(list.size() - MAX_LINES)).append(" lembretes (lembrete -ls)\n");
            sb.setSpan(new ForegroundColorSpan(GREEN), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return sb;
    }
}
