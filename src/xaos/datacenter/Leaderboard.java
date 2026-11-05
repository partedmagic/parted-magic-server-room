package xaos.datacenter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xaos.Towns;
import xaos.utils.Log;

/**
 * Posting a sale to the world leaderboard on partedmagic.com (Patrick,
 * 2026-11-04: "the person with the biggest buyout is #1 on the leaderboard").
 * Steam's own leaderboards need its closed library, which the GPL rules out, so
 * every copy of the game posts to one page there.
 *
 * Only when the player asks, on the last screen, and only the company name they
 * type, the price, the days and the site type.  It runs on a thread of its own
 * so the game never waits on the network; CommandPanel watches getState.  The
 * address comes from towns.ini (LEADERBOARD_URL, LEADERBOARD_PAGE): empty turns
 * it off.  There is no secret to protect it, since the code is public: the
 * checks are on the server (site/pmsr-leaderboard in the project).
 */
public final class Leaderboard {

    public static final int STATE_IDLE = 0;
    public static final int STATE_SENDING = 1;
    public static final int STATE_POSTED = 2;
    public static final int STATE_HAS_BIGGER = 3;
    public static final int STATE_BAD_NAME = 4;
    public static final int STATE_BUSY = 5;
    public static final int STATE_REFUSED = 6;
    public static final int STATE_NO_REPLY = 7;

    public static final int NAME_MAX = 30;

    private static final String URL_KEY = "LEADERBOARD_URL"; //$NON-NLS-1$
    private static final String PAGE_KEY = "LEADERBOARD_PAGE"; //$NON-NLS-1$
    private static final Pattern RANK = Pattern.compile("\"rank\":(\\d+)"); //$NON-NLS-1$
    private static final Pattern TOTAL = Pattern.compile("\"total\":(\\d+)"); //$NON-NLS-1$
    private static final Pattern CODE = Pattern.compile("\"code\":\"(\\w+)\""); //$NON-NLS-1$

    private static volatile int state = STATE_IDLE;
    private static volatile int rank;
    private static volatile int total;
    private static volatile String lastName;

    private Leaderboard() {
    }

    private static String getSetting(String sKey) {
        String s = Towns.getPropertiesString(sKey);
        return s == null ? "" : s.trim(); //$NON-NLS-1$
    }

    /**
     * @return true when this copy of the game has somewhere to post
     */
    public static boolean isOn() {
        return getSetting(URL_KEY).startsWith("http"); //$NON-NLS-1$
    }

    /**
     * @return true when there is a page to send the player to
     */
    public static boolean hasPage() {
        return getSetting(PAGE_KEY).startsWith("http"); //$NON-NLS-1$
    }

    public static void reset() {
        state = STATE_IDLE;
        rank = 0;
        total = 0;
        lastName = null;
    }

    public static int getState() {
        return state;
    }

    public static int getRank() {
        return rank;
    }

    public static int getTotal() {
        return total;
    }

    /**
     * @return the last name sent, null before the first
     */
    public static String getLastName() {
        return lastName;
    }

    /**
     * Sends a sale on a thread of its own
     */
    public static void post(final String sCompany, int iPrice, int iDays, String sSite) {
        if (state == STATE_SENDING || !isOn()) {
            return;
        }
        lastName = sCompany;
        state = STATE_SENDING;
        final String sBody = "{\"company\":" + jsonString(sCompany) + ",\"price\":" + iPrice + ",\"days\":" + iDays + ",\"site\":" + jsonString(sSite) + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        final String sUrl = getSetting(URL_KEY);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                send(sUrl, sBody);
            }
        }, "Leaderboard"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    private static void send(String sUrl, String sBody) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(sUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
                    .header("User-Agent", "PartedMagicServerRoom") //$NON-NLS-1$ //$NON-NLS-2$
                    .POST(HttpRequest.BodyPublishers.ofString(sBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            state = readReply(response.statusCode(), response.body());
        } catch (IOException | IllegalArgumentException e) {
            Log.log(Log.LEVEL_ERROR, "Leaderboard: " + e, "Leaderboard"); //$NON-NLS-1$ //$NON-NLS-2$
            state = STATE_NO_REPLY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = STATE_NO_REPLY;
        }
    }

    /**
     * @return the state the server's reply puts the post in
     */
    static int readReply(int iStatus, String sBody) {
        if (iStatus == 200) {
            Matcher mRank = RANK.matcher(sBody);
            Matcher mTotal = TOTAL.matcher(sBody);
            if (!mRank.find() || !mTotal.find()) {
                return STATE_NO_REPLY;
            }
            rank = Integer.parseInt(mRank.group(1));
            total = Integer.parseInt(mTotal.group(1));
            return sBody.contains("\"saved\":true") ? STATE_POSTED : STATE_HAS_BIGGER; //$NON-NLS-1$
        }
        Matcher mCode = CODE.matcher(sBody);
        String sCode = mCode.find() ? mCode.group(1) : ""; //$NON-NLS-1$
        Log.log(Log.LEVEL_DEBUG, "Leaderboard reply " + iStatus + " " + sCode, "Leaderboard"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (sCode.equals("pmsr_bad_name") || sCode.equals("pmsr_blocked_name")) { //$NON-NLS-1$ //$NON-NLS-2$
            return STATE_BAD_NAME;
        }
        if (sCode.equals("pmsr_busy")) { //$NON-NLS-1$
            return STATE_BUSY;
        }
        return sCode.startsWith("pmsr_") ? STATE_REFUSED : STATE_NO_REPLY; //$NON-NLS-1$
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\""); //$NON-NLS-1$
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < ' ') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Opens the leaderboard's page in the player's browser.  Through the system's
     * own opener: java.awt.Desktop can hang on a Mac beside the game's window.
     */
    public static void openPage() {
        String sPage = getSetting(PAGE_KEY);
        if (!sPage.startsWith("http")) { //$NON-NLS-1$
            return;
        }
        String sOs = System.getProperty("os.name", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
        ProcessBuilder builder;
        if (sOs.contains("win")) { //$NON-NLS-1$
            builder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", sPage); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (sOs.contains("mac")) { //$NON-NLS-1$
            builder = new ProcessBuilder("open", sPage); //$NON-NLS-1$
        } else {
            builder = new ProcessBuilder("xdg-open", sPage); //$NON-NLS-1$
        }
        try {
            builder.start();
        } catch (IOException e) {
            Log.log(Log.LEVEL_ERROR, "Leaderboard: " + e, "Leaderboard"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
