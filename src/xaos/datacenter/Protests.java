package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;

/**
 * Protests (docs/stakes.md, step 5, the stakes pass and the rework of
 * 2026-10-30).
 *
 * From day 3 about one dawn in three sets a protest for that day, at any hour
 * after dawn.  Three to five protesters, and one more for every eight racks
 * of the hall, walk in from the edge of the map to a
 * spot 4 to 7 cells from the middle of the hall, in plain sight, and stay 6
 * to 10 hours.  Every two hours the news runs the story (a point of
 * reputation) while anyone stands there, one or two more join (up to five,
 * and one more for every two days of the site and for every eight racks,
 * twelve at most and one more for every four racks), they chant,
 * and a raid goes in: one protester, and one more for every three in the
 * crowd, but no more than one for every eight racks of the hall (at least
 * one; getRaidCap), all at once, each to its own rack, network switch or
 * grid feed, which takes five minutes to wreck, or to a used drive left
 * lying about unerased, which one walks off with: a data breach on the
 * news.  A wreck makes the news too and costs more.  No two on the map look
 * alike (Patrick, 2026-11-04: "it looks really dumb having the exact same
 * protesters standing next to each other"): each of the 25 types is a look of
 * its own, a newcomer takes one nobody is wearing (getFreeLook), and with all
 * 25 out nobody else comes.  They are FRIENDLY livings that never wander
 * (livingentities.xml), so this class walks them (setDestination); nothing
 * can hurt them.  Walls
 * stop them, but since 2026-11-01 doors do not (Patrick: "Get rid of the
 * door stops people. only guards stop people."): they walk through a door
 * open or shut, as the thieves do (walksThroughDoors, LivingEntity).  A
 * raider with no way in gives up after an hour.
 *
 * A guard post keeps a guard on the site all day (since 2026-11-01; the
 * first posts hired a guard for a day or a night shift).  He is one of the
 * crew (Crew.isGuard): he eats, sleeps in a bunk, needs a roofed bed and can
 * quit or be killed like the rest, and he guards only while he is not eating
 * or sleeping (isOnDuty), which leaves openings.  On duty he chases a
 * protester inside the site that he or a running camera sees, and one he
 * reaches leaves without doing damage, but he catches one at a time, so a
 * raid of several gets some through.  Next he goes after a copper thief he
 * or a camera sees (Thieves.findThief).  With no one inside he walks out to
 * the crowd and talks them into going home, one at a time, two hours each,
 * slower than the crowd grows.  Once no one is left at the spot or inside,
 * the protest is over.
 */
public final class Protests {

    private static final int PROTEST_FROM_DAY = 3;
    // A dawn's chance of a protest grows with the site: a small hall draws few
    // (Patrick's "protests that grow with the site"; 35% flat until 2026-11-04
    // cost a 4-rack site more in parts than it could earn)
    private static final int PROTEST_BASE_PCT = 15;
    private static final int PROTEST_PCT_PER_RACK = 2;
    private static final int PROTEST_MAX_PCT = 45;
    // One protest wrecks at most one thing and one more for every RACKS_PER_WRECK
    // racks: with no limit, one could leave a 4-rack site with nothing running
    private static final int RACKS_PER_WRECK = 4;
    private static final int MAX_CROWD = 12;
    private static final int WAVE_TURNS = 2 * World.TIME_MODIFIER_HOUR;    // two hours between waves
    private static final String[] PROTESTER_TYPES = new String[25];
    static {
        for (int i = 0; i < PROTESTER_TYPES.length; i++) {
            PROTESTER_TYPES[i] = "protester" + (i + 1); //$NON-NLS-1$
        }
    }
    private static final int CROWD_REACH = 6;
    private static final int SPOT_MIN = 4;
    private static final int SPOT_MAX = 7;
    private static final int CAMERA_RANGE = 8;
    private static final int GUARD_SIGHT = 6;
    static final int GUARD_PAY = 2;
    private static final int WRECK_TURNS = 10;       // 5 minutes at the target
    private static final int GIVE_UP_TURNS = 120;    // an hour with no way in
    private static final int REPATH_TURNS = 20;
    private static final int TALK_TURNS = 240;       // two hours to talk one in the crowd into going home
    private static final String USED_DRIVE = "dcuseddrive"; //$NON-NLS-1$
    private static final int REPUTATION_BREACH = 5;
    // A raid is at most one protester for every eight racks of the hall,
    // rounded, and at least one (Patrick, 2026-11-01, "Choice 1" after the
    // play to day 30: the same raids cost a 4-rack site most of its earnings
    // and a 37-rack site almost nothing)
    private static final int RACKS_PER_RAIDER = 8;

    // A protester's errand
    private static final int GO_GATHER = 1;
    private static final int GO_RAID = 2;
    private static final int GO_WRECK = 3;
    private static final int GO_HOME = 4;

    // The protest of the day: the turn of the day it starts (-1 when none is
    // coming), the turns it has left (counted from when the group arrives)
    // and the turn count at which the raiders set off, the spot (cell key)
    // the group gathers at and the edge cell it came in by and leaves by
    private static int protestStart = -1;
    private static int protestLeft;
    private static int protestRaid;      // the protest's length in turns: its waves follow the clock
    private static int protestSpot = -1;
    private static int protestEntry = -1;

    // The hall's racks at the last turn, for the size of a raid
    private static int hallRacks;
    // What this protest has wrecked (not saved: a load gives it its limit back)
    private static int wrecksThisProtest;

    // The protesters: living ID, errand, target cell key, timer
    private static final ArrayList<int[]> alProtesters = new ArrayList<int[]>();

    // The guards, by their post's cell key: living ID, the protester he
    // chases (-1 for none), timer.  A post whose guard left or died keeps
    // its entry, marked VACANT with the day he went in the timer, and hires
    // the next guard at the dawn after
    private static final HashMap<Integer, int[]> hmGuards = new HashMap<Integer, int[]>();
    private static final int VACANT = -2;

    // The guards at their posts at the last count: each is paid
    private static int posts;

    // A site hires three guards at most (Patrick, 2026-11-01, after the
    // measurements: "does the game get too easy with 4 guards? If so, maybe
    // cap the guards at 3"): the build menu refuses a fourth post
    // (DataCenter.getPlaceLimitReason), and a post past the third in install
    // order, from an older save or two ordered at once, hires no one
    static final int MAX_GUARDS = 3;
    private static final HashSet<Integer> hsUnstaffed = new HashSet<Integer>();
    private static int postsInstalled;
    // Posts with no guard because the site has no donut shop (Leisure), by
    // item ID: guards come only to a site that has one
    private static final HashSet<Integer> hsWaitDonuts = new HashSet<Integer>();

    private Protests() {
    }

    static void reset() {
        posts = 0;
        postsInstalled = 0;
        hsUnstaffed.clear();
        hsWaitDonuts.clear();
        protestStart = -1;
        protestLeft = 0;
        protestRaid = 0;
        protestSpot = -1;
        protestEntry = -1;
        alProtesters.clear();
        hmGuards.clear();
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(protestStart);
        out.writeInt(protestLeft);
        out.writeInt(protestRaid);
        out.writeInt(protestSpot);
        out.writeInt(protestEntry);
        out.writeInt(alProtesters.size());
        for (int[] p : alProtesters) {
            for (int i = 0; i < 4; i++) {
                out.writeInt(p[i]);
            }
        }
        out.writeInt(hmGuards.size());
        for (Map.Entry<Integer, int[]> entry : hmGuards.entrySet()) {
            out.writeInt(entry.getKey().intValue());
            for (int i = 0; i < 3; i++) {
                out.writeInt(entry.getValue()[i]);
            }
        }
    }

    static void readExternal(ObjectInput in) throws IOException {
        protestStart = in.readInt();
        protestLeft = in.readInt();
        protestRaid = in.readInt();
        protestSpot = in.readInt();
        protestEntry = in.readInt();
        for (int n = in.readInt(); n > 0; n--) {
            alProtesters.add(new int[] {in.readInt(), in.readInt(), in.readInt(), in.readInt()});
        }
        for (int n = in.readInt(); n > 0; n--) {
            int iKey = in.readInt();
            hmGuards.put(Integer.valueOf(iKey), new int[] {in.readInt(), in.readInt(), in.readInt()});
        }
    }

    /**
     * Whether a door stops this living, open or shut (LivingEntity): the bear
     * (Bear), an animal that cannot open one.  Until 2026-11-01 doors stopped
     * the protesters and the copper thieves too
     */
    public static boolean isStoppedByDoors(String sIniHeader) {
        return "dcbear".equals(sIniHeader); //$NON-NLS-1$
    }

    /**
     * @return true for the people who walk through a door open or shut, as
     *         the crew does: the protesters and the copper thieves (Patrick,
     *         2026-11-01: "only guards stop people"; LivingEntity stops other
     *         friendly livings at a shut door)
     */
    public static boolean walksThroughDoors(String sIniHeader) {
        return isProtester(sIniHeader) || (sIniHeader != null && sIniHeader.startsWith("dcthief")); //$NON-NLS-1$
    }

    /**
     * @return true for a protester's type
     */
    public static boolean isProtester(String sIniHeader) {
        for (String s : PROTESTER_TYPES) {
            if (s.equals(sIniHeader)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the guards on the payroll: those at their posts, on duty or
     *         not, three at most
     */
    static int getGuards() {
        return posts;
    }

    /**
     * @return the guard posts installed, staffed or not
     */
    static int getPostsInstalled() {
        return postsInstalled;
    }

    /**
     * @return whether this guard post is past the third and keeps no guard
     */
    static boolean isUnstaffed(Item post) {
        return hsUnstaffed.contains(Integer.valueOf(post.getID()));
    }

    /**
     * @return whether this guard post has no guard for want of a donut shop
     */
    static boolean isWaitingForDonuts(Item post) {
        return hsWaitDonuts.contains(Integer.valueOf(post.getID()));
    }

    /**
     * @return the orange row's part when a guard post has no guard for want
     *         of a donut shop, or null
     */
    static String getDonutStatus() {
        return hsWaitDonuts.isEmpty() ? null : Messages.getString("DataCenter.statusDonuts"); //$NON-NLS-1$
    }

    /**
     * @return the status line's part, or null with no protesters around
     */
    static String getStatus() {
        int n = getActive();
        if (n == 0) {
            return null;
        }
        return MessageFormat.format(Messages.getString("DataCenter.statusProtest"), n); //$NON-NLS-1$
    }

    /**
     * @return the protesters at the spot or inside the site (not those
     *         walking home)
     */
    private static int getActive() {
        int n = 0;
        for (int[] p : alProtesters) {
            if (p[1] != GO_HOME) {
                n++;
            }
        }
        return n;
    }

    /**
     * Dawn, from day 3: about one day in three a protest is set for that day,
     * at an hour from 1 to 20 after dawn, night or day (not in a tutorial)
     */
    static void newDay(boolean bTutorial) {
        if (bTutorial || protestStart >= 0 || protestLeft > 0 || !alProtesters.isEmpty() || Game.getWorld().getDate().getDaysSinceStart() < PROTEST_FROM_DAY) {
            return;
        }
        if (Utils.getRandomBetween(1, 100) <= Math.min(PROTEST_MAX_PCT, PROTEST_BASE_PCT + hallRacks * PROTEST_PCT_PER_RACK)) {
            protestStart = Utils.getRandomBetween(1, 20) * World.TIME_MODIFIER_HOUR;
        }
    }

    /**
     * Every turn, after DataCenter.updateHall: a coming protest starts at its
     * hour, the protesters and the guards go about their errands
     */
    static void nextTurn(ArrayList<Item> alHallRacks, ArrayList<Item> alPosts, ArrayList<Item> alCameras) {
        hallRacks = alHallRacks.size();
        if (protestStart >= 0 && Game.getWorld().getTurn() >= protestStart) {
            protestStart = -1;
            startProtest(alHallRacks);
        }
        if (protestLeft > 0 && getActive() == 0) {
            // Every one of them was sent home or slipped in and left: the
            // protest is over (a clock left running would also stop the next
            // one coming)
            protestLeft = 0;
            say("DataCenter.protestOver", ColorGL.GREEN, null); //$NON-NLS-1$
        }
        // The clock starts when the group arrives and runs on after that, when
        // none may be left at the spot.  After the first hour some slip in;
        // every two hours after, the news runs it, more join, they chant and
        // some slip in again
        if (protestLeft > 0 && (protestLeft < protestRaid || hasArrived())) {
            protestLeft--;
            int iElapsed = protestRaid - protestLeft;
            if (protestLeft > 0 && iElapsed >= World.TIME_MODIFIER_HOUR && (iElapsed - World.TIME_MODIFIER_HOUR) % WAVE_TURNS == 0) {
                if (iElapsed > World.TIME_MODIFIER_HOUR) {
                    onTheNews();
                    grow();
                    chant();
                }
                sendRaiders();
            }
            if (protestLeft == 0) {
                for (int[] p : alProtesters) {
                    if (p[1] == GO_GATHER) {
                        goHome(p);
                    }
                }
                say("DataCenter.protestOver", ColorGL.GREEN, null); //$NON-NLS-1$
            }
        }
        if (!alProtesters.isEmpty()) {
            moveProtesters();
        }
        updateGuards(alPosts, alCameras);
    }

    private static void startProtest(ArrayList<Item> alHallRacks) {
        if (alHallRacks.isEmpty()) {
            return;
        }
        Point3DShort spot = findSpot(getHallMiddle(alHallRacks));
        if (spot == null) {
            return;
        }
        // In by the nearest of a dozen edge cells, so the walk is not across
        // the whole map
        Point3DShort border = null;
        for (int i = 0; i < 12; i++) {
            Point3DShort p3d = World.getRandomBorderPoint(World.getCell(spot).getAstarZoneID());
            if (p3d != null && (border == null || Math.abs(p3d.x - spot.x) + Math.abs(p3d.y - spot.y) < Math.abs(border.x - spot.x) + Math.abs(border.y - spot.y))) {
                border = new Point3DShort(p3d.x, p3d.y, p3d.z);
            }
        }
        if (border == null) {
            return;
        }
        // A bigger site draws a bigger crowd: one more for every eight racks
        // (2026-11-01, with the cap of three guards: on a 37-rack site three
        // guards stopped nearly every protest until it did)
        int n = Utils.getRandomBetween(3, 5) + hallRacks / RACKS_PER_RAIDER;
        for (int i = 0; i < n; i++) {
            join(border, spot);
        }
        if (alProtesters.isEmpty()) {
            return;
        }
        protestSpot = cellKey(spot.x, spot.y, spot.z);
        protestEntry = cellKey(border.x, border.y, border.z);
        protestLeft = Utils.getRandomBetween(6, 10) * World.TIME_MODIFIER_HOUR;
        wrecksThisProtest = 0;
        protestRaid = protestLeft;
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " protest of " + alProtesters.size() + " at " + spot.x + "," + spot.y + "," + spot.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.protestStart"), slogan(World.getLivingEntityByID(alProtesters.get(0)[0]))), ColorGL.ORANGE, new Point3DShort(spot.x, spot.y, spot.z)); //$NON-NLS-1$
    }

    /**
     * One more protester walks in from the border to the crowd
     *
     * @return false if nobody came: every look is on the map already
     */
    private static boolean join(Point3DShort border, Point3DShort spot) {
        String sLook = getFreeLook();
        if (sLook == null) {
            return false;
        }
        LivingEntity le = World.addNewLiving(sLook, LivingEntity.TYPE_FRIENDLY, true, border.x, border.y, border.z, true);
        if (le == null) {
            return false;
        }
        name(le);
        Point3DShort p3d = near(spot);
        le.setDestination(p3d);
        alProtesters.add(new int[] {le.getID(), GO_GATHER, cellKey(p3d.x, p3d.y, p3d.z), 0});
        return true;
    }

    /**
     * @return a protester's type nobody on the map is wearing, picked at
     *         random, or null when all of them are out.  Those on their way
     *         home count until they have left.
     */
    private static String getFreeLook() {
        ArrayList<String> alFree = new ArrayList<String>(Arrays.asList(PROTESTER_TYPES));
        for (int[] p : alProtesters) {
            LivingEntity le = World.getLivingEntityByID(p[0]);
            if (le != null) {
                alFree.remove(le.getIniHeader());
            }
        }
        return alFree.isEmpty() ? null : alFree.get(Utils.getRandomBetween(0, alFree.size() - 1));
    }

    /**
     * @return the days since the site started
     */
    private static int getSiteDays() {
        return Game.getWorld().getDate().getDaysSinceStart();
    }

    /**
     * The middle of the hall: the rack nearest the racks' average cell
     */
    private static Point3DShort getHallMiddle(ArrayList<Item> alHallRacks) {
        long lX = 0;
        long lY = 0;
        for (Item rack : alHallRacks) {
            lX += rack.getX();
            lY += rack.getY();
        }
        int x = (int) (lX / alHallRacks.size());
        int y = (int) (lY / alHallRacks.size());
        Item best = alHallRacks.get(0);
        for (Item rack : alHallRacks) {
            if (Math.abs(rack.getX() - x) + Math.abs(rack.getY() - y) < Math.abs(best.getX() - x) + Math.abs(best.getY() - y)) {
                best = rack;
            }
        }
        return new Point3DShort(best.getX(), best.getY(), best.getZ());
    }

    /**
     * Every two hours while anyone stands at the spot, the protest is on the
     * news: a point of reputation
     */
    private static void onTheNews() {
        for (int[] p : alProtesters) {
            if (p[1] == GO_GATHER) {
                int iReputation = DataCenter.newsHit(1);
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.protestOnNews"), iReputation), ColorGL.ORANGE, getSpotPoint()); //$NON-NLS-1$
                return;
            }
        }
    }

    private static Point3DShort getSpotPoint() {
        if (protestSpot == -1) {
            return null;
        }
        int[] c = cellOf(protestSpot);
        return new Point3DShort((short) c[0], (short) c[1], (short) c[2]);
    }

    /**
     * Every two hours one or two more join the crowd, up to five and one
     * more for every two days of the site and for every eight racks
     * (MAX_CROWD and one more for every four racks at most)
     */
    private static void grow() {
        if (protestSpot == -1 || protestEntry == -1) {
            return;
        }
        int[] s = cellOf(protestSpot);
        int[] e = cellOf(protestEntry);
        Point3DShort spot = new Point3DShort((short) s[0], (short) s[1], (short) s[2]);
        Point3DShort border = new Point3DShort((short) e[0], (short) e[1], (short) e[2]);
        int iJoined = 0;
        int iMax = Math.min(MAX_CROWD + hallRacks / 4, 5 + getSiteDays() / 2 + hallRacks / RACKS_PER_RAIDER);
        for (int i = Utils.getRandomBetween(1, 2); i > 0 && getActive() < iMax; i--) {
            if (join(border, spot)) {
                iJoined++;
            }
        }
        if (iJoined > 0) {
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.protestGrows"), getActive()), ColorGL.ORANGE, spot); //$NON-NLS-1$
        }
    }

    /**
     * The crowd chants one of its slogans, in the log: someone else's each
     * wave, with no dice drawn
     */
    private static void chant() {
        ArrayList<LivingEntity> alGathered = new ArrayList<LivingEntity>();
        for (int[] p : alProtesters) {
            if (p[1] == GO_GATHER) {
                LivingEntity le = World.getLivingEntityByID(p[0]);
                if (le != null) {
                    alGathered.add(le);
                }
            }
        }
        if (!alGathered.isEmpty()) {
            LivingEntity le = alGathered.get(Game.getWorld().getTurn() / WAVE_TURNS % alGathered.size());
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.protestChant"), slogan(le)), ColorGL.ORANGE, new Point3DShort(le.getX(), le.getY(), le.getZ())); //$NON-NLS-1$
        }
    }

    /**
     * After a load: a living's name is not saved (LivingEntityData), so the
     * protesters get their slogans back
     */
    static void refresh() {
        for (int[] p : alProtesters) {
            LivingEntity le = World.getLivingEntityByID(p[0]);
            if (le != null) {
                name(le);
            }
        }
        // A save from before guards were crew holds a shift guard who is not
        // a citizen, and the load leaves him out (LivingEntity.loadLivings):
        // his post forgets him and hires a crew guard at once
        hmGuards.values().removeIf(g -> g[1] != VACANT && World.getLivingEntityByID(g[0]) == null);
    }

    /**
     * A protester is named by the slogan of its look, one for each of the 25,
     * so it is the same again after a load
     */
    private static void name(LivingEntity le) {
        le.getLivingEntityData().setName(MessageFormat.format(Messages.getString("DataCenter.protesterName"), slogan(le))); //$NON-NLS-1$
    }

    /**
     * After the first hour and every two hours after: some of the gathered
     * protesters slip in together (one, and one more for every three in the
     * crowd, but no more than getRaidCap), each to its own target while there
     * are enough
     */
    private static void sendRaiders() {
        ArrayList<Item> alTargets = getTargets();
        if (alTargets.isEmpty() || wrecksThisProtest >= 1 + hallRacks / RACKS_PER_WRECK) {
            return;
        }
        ArrayList<int[]> alReady = new ArrayList<int[]>();
        for (int[] p : alProtesters) {
            if (p[1] == GO_GATHER) {
                alReady.add(p);
            }
        }
        int n = Math.min(alReady.size(), Math.min(1 + alReady.size() / 3, getRaidCap()));
        ArrayList<Item> alFree = new ArrayList<Item>(alTargets);
        for (int i = 0; i < n; i++) {
            int[] p = alReady.remove(Utils.getRandomBetween(0, alReady.size() - 1));
            if (alFree.isEmpty()) {
                alFree.addAll(alTargets);
            }
            Item target = alFree.remove(Utils.getRandomBetween(0, alFree.size() - 1));
            p[1] = GO_RAID;
            p[2] = cellKey(target.getX(), target.getY(), target.getZ());
            p[3] = 0;
            LivingEntity le = World.getLivingEntityByID(p[0]);
            if (le != null) {
                Point3DShort p3d = nextTo(target, le);
                if (p3d != null) {
                    le.setDestination(p3d);
                }
            }
        }
        if (n > 0) {
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + n + " protesters slip in"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.protestRaid"), n), ColorGL.RED); //$NON-NLS-1$
        }
    }

    /**
     * @return the most protesters one raid sends: one for every eight racks
     *         in the hall, rounded, and at least one (4 racks: 1, 12: 2, 20:
     *         3, 28: 4, 36: 5); the crowd's own limit is one and one more
     *         for every three
     */
    static int getRaidCap() {
        return Math.max(1, (hallRacks + RACKS_PER_RAIDER / 2) / RACKS_PER_RAIDER);
    }

    private static void moveProtesters() {
        for (int i = alProtesters.size() - 1; i >= 0; i--) {
            int[] p = alProtesters.get(i);
            LivingEntity le = World.getLivingEntityByID(p[0]);
            if (le == null) {
                alProtesters.remove(i);
                continue;
            }
            boolean bIdle = le.getPath().isEmpty() && !le.isWaitingForPath();
            if (p[1] == GO_RAID) {
                Item target = getTargetAt(p[2]);
                if (target == null) {
                    goHome(p);
                } else if (bIdle && isNextTo(le, target)) {
                    p[1] = GO_WRECK;
                    p[3] = WRECK_TURNS;
                } else if (bIdle) {
                    p[3]++;
                    if (p[3] >= GIVE_UP_TURNS) {
                        goHome(p);
                        say("DataCenter.protestBlocked", ColorGL.GREEN, le); //$NON-NLS-1$
                    } else if (p[3] % REPATH_TURNS == 0) {
                        Point3DShort p3d = nextTo(target, le);
                        if (p3d != null) {
                            le.setDestination(p3d);
                        }
                    }
                }
            } else if (p[1] == GO_WRECK) {
                p[3]--;
                if (p[3] <= 0) {
                    wreck(getTargetAt(p[2]));
                    goHome(p);
                }
            }
            if (p[1] == GO_HOME && bIdle) {
                // Back out the way they came, then by any edge cell, and gone
                // once at the edge or after 30 tries with no way out
                Point3DShort p3d = le.getCoordinates();
                if (p3d.x == 0 || p3d.y == 0 || p3d.x == World.MAP_WIDTH - 1 || p3d.y == World.MAP_HEIGHT - 1 || p[3] > 30) {
                    le.delete();
                    alProtesters.remove(i);
                    continue;
                }
                p[3]++;
                Point3DShort border = null;
                if (protestEntry != -1 && p[3] <= 15) {
                    int[] c = cellOf(protestEntry);
                    if (World.getCell(c[0], c[1], c[2]).getAstarZoneID() == World.getCell(p3d).getAstarZoneID()) {
                        border = new Point3DShort((short) c[0], (short) c[1], (short) c[2]);
                    }
                }
                if (border == null) {
                    border = World.getRandomBorderPoint(World.getCell(p3d).getAstarZoneID());
                }
                if (border == null) {
                    le.delete();
                    alProtesters.remove(i);
                    continue;
                }
                le.setDestination(border);
            }
        }
    }

    /**
     * Each guard post keeps a guard on the site all day (Patrick, 2026-11-01:
     * "the guard should be there all the time. Not a night and day guard.
     * The guard needs to sleep and eat too like everybody else. That will
     * leave an opening for the protesters and thieves").  A new post hires
     * one at once, beside it; a post whose guard quit or died hires the next
     * at the dawn after he went, so a guard who quits at dawn leaves the post
     * empty a whole day.  While on duty he chases a protester inside the site
     * that he or a running camera sees and sends home one he reaches; eating
     * and sleeping, as the crew does, he guards nothing.  A guard whose post
     * is gone leaves the site.
     */
    private static void updateGuards(ArrayList<Item> alPosts, ArrayList<Item> alCameras) {
        postsInstalled = alPosts.size();
        ArrayList<Item> alByID = new ArrayList<Item>(alPosts);
        alByID.sort((a, b) -> Integer.compare(a.getID(), b.getID()));
        HashSet<Integer> hsPosts = new HashSet<Integer>();
        hsUnstaffed.clear();
        hsWaitDonuts.clear();
        Boolean bDonuts = null;
        int iStaffed = 0;
        for (Item post : alByID) {
            Integer iKey = Integer.valueOf(cellKey(post.getX(), post.getY(), post.getZ()));
            if (hsPosts.size() >= MAX_GUARDS) {
                // Past the third post: no guard (one it had leaves below)
                hsUnstaffed.add(Integer.valueOf(post.getID()));
                continue;
            }
            hsPosts.add(iKey);
            int[] g = hmGuards.get(iKey);
            LivingEntity guard = g == null ? null : World.getLivingEntityByID(g[0]);
            if (guard == null) {
                int iDay = Game.getWorld().getDate().getDaysSinceStart();
                if (g != null && g[1] != VACANT) {
                    g[1] = VACANT;
                    g[2] = iDay;
                } else if (g == null || iDay > g[2]) {
                    // Guards come only to a site with a donut shop
                    if (bDonuts == null) {
                        bDonuts = Boolean.valueOf(Leisure.hasDonutShop());
                    }
                    if (bDonuts.booleanValue()) {
                        hire(post, iKey);
                    } else {
                        hsWaitDonuts.add(Integer.valueOf(post.getID()));
                    }
                }
                continue;
            }
            iStaffed++;
            if (isOnDuty(guard)) {
                chase(guard, g, post, alCameras);
            } else {
                g[1] = -1;
            }
        }
        posts = iStaffed;
        ArrayList<Integer> alGone = new ArrayList<Integer>();
        for (Integer iKey : hmGuards.keySet()) {
            if (!hsPosts.contains(iKey)) {
                alGone.add(iKey);
            }
        }
        for (Integer iKey : alGone) {
            LivingEntity guard = World.getLivingEntityByID(hmGuards.remove(iKey)[0]);
            if (guard != null) {
                guard.deleteLeaving();
            }
        }
    }

    /**
     * A new guard for the post, one of the crew, beside it
     */
    private static void hire(Item post, Integer iKey) {
        Point3DShort p3d = nextTo(post, null);
        if (p3d == null) {
            return;
        }
        LivingEntity guard = World.addNewLiving(Crew.getGuardType(), LivingEntity.TYPE_CITIZEN, true, p3d.x, p3d.y, p3d.z, true);
        if (!(guard instanceof Citizen)) {
            return;
        }
        hmGuards.put(iKey, new int[] {guard.getID(), -1, 0});
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " guard hired at " + p3d.x + "," + p3d.y + "," + p3d.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.guardHired"), ((Citizen) guard).getCitizenData().getFullName()), ColorGL.GREEN, p3d); //$NON-NLS-1$
    }

    /**
     * @return true while the guard is up and free: not eating, sleeping or on
     *         the way to either (any task the crew's needs give him).  The
     *         rest of the time is the opening protesters and thieves get
     */
    static boolean isOnDuty(LivingEntity guard) {
        return guard instanceof Citizen && ((Citizen) guard).getCurrentTask() == null;
    }

    private static void chase(LivingEntity guard, int[] g, Item post, ArrayList<Item> alCameras) {
        boolean bIdle = guard.getPath().isEmpty() && !guard.isWaitingForPath();
        int[] target = g[1] == -1 ? null : getProtester(g[1]);
        if (target != null && target[1] == GO_HOME) {
            target = null;
        }
        // A protester inside the site comes first, even over one he is
        // walking to at the crowd
        if (target == null || target[1] == GO_GATHER) {
            int[] raider = findRaider(guard, alCameras);
            if (raider != null) {
                target = raider;
            }
        }
        // Then a copper thief he or a camera sees, before the crowd: he walks
        // to it, and Thieves chases it off once he is near (2026-11-01, when
        // walls stopped keeping thieves out)
        if (target == null || target[1] == GO_GATHER) {
            Point3DShort thief = Thieves.findThief(guard, alCameras, GUARD_SIGHT, CAMERA_RANGE);
            if (thief != null) {
                g[1] = -1;
                if (bIdle || Bear.now() % REPATH_TURNS == 0) {
                    guard.setDestination(thief);
                }
                return;
            }
        }
        // No one inside: he walks out to the crowd and talks them into going
        // home, one at a time
        if (target == null) {
            target = findGathered(guard);
        }
        if (target == null) {
            g[1] = -1;
            if (bIdle && distance(guard.getCoordinates(), post.getCoordinates()) > 1) {
                Point3DShort p3d = nextTo(post, guard);
                if (p3d != null) {
                    guard.setDestination(p3d);
                }
            }
            return;
        }
        LivingEntity le = World.getLivingEntityByID(target[0]);
        if (le == null) {
            g[1] = -1;
            return;
        }
        if (g[1] != target[0]) {
            g[1] = target[0];
            g[2] = 0;
            guard.setDestination(le.getCoordinates());
            return;
        }
        if (distance(guard.getCoordinates(), le.getCoordinates()) <= 1) {
            boolean bInside = target[1] != GO_GATHER;
            if (!bInside) {
                // Talking one in the crowd into going home takes two hours
                // (g[2] counts it up from below zero); a raider inside does
                // not wait for that
                if (g[2] >= 0) {
                    g[2] = -TALK_TURNS;
                    return;
                }
                g[2]++;
                if (g[2] < 0) {
                    return;
                }
            }
            goHome(target);
            le.clearPath();
            g[1] = -1;
            say(bInside ? "DataCenter.protestCaught" : "DataCenter.protestTalked", ColorGL.GREEN, le); //$NON-NLS-1$ //$NON-NLS-2$
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " guard sent a protester away" + (bInside ? "" : " from the crowd")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            return;
        }
        g[2]++;
        if (bIdle || g[2] % REPATH_TURNS == 0) {
            guard.setDestination(le.getCoordinates());
        }
    }

    /**
     * @return the nearest protester inside the site that the guard sees, or
     *         that a running camera sees (it says so), that no other guard is
     *         after; or null
     */
    private static int[] findRaider(LivingEntity guard, ArrayList<Item> alCameras) {
        int[] best = null;
        int iBest = Integer.MAX_VALUE;
        boolean bCamera = false;
        for (int[] p : alProtesters) {
            if ((p[1] != GO_RAID && p[1] != GO_WRECK) || isChasedByOther(p[0], guard.getID())) {
                continue;
            }
            LivingEntity le = World.getLivingEntityByID(p[0]);
            if (le == null) {
                continue;
            }
            int d = distance(guard.getCoordinates(), le.getCoordinates());
            boolean bSeen = d <= GUARD_SIGHT;
            boolean bOnCamera = false;
            for (Item camera : alCameras) {
                if (distance(camera.getCoordinates(), le.getCoordinates()) <= CAMERA_RANGE) {
                    bOnCamera = true;
                    break;
                }
            }
            if ((bSeen || bOnCamera) && d < iBest) {
                iBest = d;
                best = p;
                bCamera = !bSeen;
            }
        }
        if (best != null && bCamera && !isChased(best[0])) {
            LivingEntity le = World.getLivingEntityByID(best[0]);
            say("DataCenter.protestSpotted", ColorGL.ORANGE, le); //$NON-NLS-1$
        }
        return best;
    }

    /**
     * @return the nearest protester in the crowd that no other guard is
     *         after, or null
     */
    private static int[] findGathered(LivingEntity guard) {
        int[] best = null;
        int iBest = Integer.MAX_VALUE;
        for (int[] p : alProtesters) {
            if (p[1] != GO_GATHER || isChasedByOther(p[0], guard.getID())) {
                continue;
            }
            LivingEntity le = World.getLivingEntityByID(p[0]);
            if (le == null) {
                continue;
            }
            int d = distance(guard.getCoordinates(), le.getCoordinates());
            if (d < iBest) {
                iBest = d;
                best = p;
            }
        }
        return best;
    }

    private static void goHome(int[] p) {
        p[1] = GO_HOME;
        p[3] = 0;
    }

    /**
     * @return true once one of the group stands at the spot, so the protest's
     *         hours start then, not while they walk in from the edge
     */
    private static boolean hasArrived() {
        for (int[] p : alProtesters) {
            if (p[1] == GO_GATHER) {
                LivingEntity le = World.getLivingEntityByID(p[0]);
                if (le != null && le.getPath().isEmpty() && !le.isWaitingForPath()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isChased(int iProtesterID) {
        for (int[] g : hmGuards.values()) {
            if (g[1] == iProtesterID) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChasedByOther(int iProtesterID, int iGuardID) {
        for (int[] g : hmGuards.values()) {
            if (g[1] == iProtesterID && g[0] != iGuardID) {
                return true;
            }
        }
        return false;
    }

    private static int[] getProtester(int iID) {
        for (int[] p : alProtesters) {
            if (p[0] == iID) {
                return p;
            }
        }
        return null;
    }

    /**
     * What protesters go for: racks, network switches and grid feeds, not
     * already wrecked, and used drives lying about that were never erased
     * (one taken is a data breach on the news)
     */
    private static ArrayList<Item> getTargets() {
        ArrayList<Item> al = new ArrayList<Item>();
        for (Item item : World.getItems().values()) {
            if (isTarget(item)) {
                al.add(item);
            }
        }
        return al;
    }

    private static boolean isTarget(Item item) {
        if (USED_DRIVE.equals(item.getIniHeader())) {
            return !item.isLocked() && World.getCell(item.getCoordinates()).getItem() == item;
        }
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        if (imi == null || imi.getDcDown() == DataCenter.DOWN_WRECKED || !DataCenter.isInstalled(item)) {
            return false;
        }
        return imi.getDcIncome() > 0 || imi.getDcPorts() > 0 || imi.getDcSource() == DataCenter.SOURCE_GRID;
    }

    private static Item getTargetAt(int iKey) {
        int[] c = cellOf(iKey);
        Item item = World.getCell(c[0], c[1], c[2]).getItem();
        return item != null && isTarget(item) ? item : null;
    }

    /**
     * Wrecks a target: a rack, a switch or the grid feed is swapped for its
     * wrecked self, which a technician repairs with spare parts (Repair)
     */
    private static void wreck(Item target) {
        if (target == null) {
            return;
        }
        if (USED_DRIVE.equals(target.getIniHeader())) {
            // A drive with a customer's data on it walks off the site
            Point3DShort p3d = new Point3DShort(target.getX(), target.getY(), target.getZ());
            target.delete();
            int iReputation = DataCenter.newsHit(REPUTATION_BREACH);
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " protester took an unerased drive, reputation " + iReputation); //$NON-NLS-1$ //$NON-NLS-2$
            }
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.protestStoleDrive"), iReputation), ColorGL.RED, p3d); //$NON-NLS-1$
            return;
        }
        ItemManagerItem imi = ItemManager.getItem(target.getIniHeader());
        String sWrecked;
        String sKey;
        if (imi.getDcIncome() > 0) {
            sWrecked = "dcrackwrecked"; //$NON-NLS-1$
            sKey = "DataCenter.protestWreckedRack"; //$NON-NLS-1$
        } else if (imi.getDcSource() == DataCenter.SOURCE_GRID) {
            sWrecked = "dcgridcut"; //$NON-NLS-1$
            sKey = "DataCenter.protestWreckedGrid"; //$NON-NLS-1$
        } else {
            sWrecked = "dcswitchwrecked"; //$NON-NLS-1$
            sKey = "DataCenter.protestWreckedSwitch"; //$NON-NLS-1$
        }
        Point3DShort p3d = DataCenter.replaceRack(target, sWrecked);
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + sWrecked + " at " + (p3d == null ? "?" : p3d.x + "," + p3d.y + "," + p3d.z)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }
        wrecksThisProtest++;
        // It makes the news: the reputation drops (DataCenter.newsHit)
        int iReputation = DataCenter.newsHit();
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString(sKey) + " " + MessageFormat.format(Messages.getString("DataCenter.protestNews"), iReputation), ColorGL.RED, p3d); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return a cell 4 to 7 cells from the middle of the hall, in plain sight
     *         of it and not under a roof, where people can stand and that a
     *         border point reaches, or null after 40 tries
     */
    private static Point3DShort findSpot(Point3DShort hall) {
        for (int i = 0; i < 40; i++) {
            double a = Math.toRadians(Utils.getRandomBetween(0, 359));
            int d = Utils.getRandomBetween(SPOT_MIN, SPOT_MAX);
            int x = hall.x + (int) Math.round(Math.cos(a) * d);
            int y = hall.y + (int) Math.round(Math.sin(a) * d);
            if (x < 1 || y < 1 || x >= World.MAP_WIDTH - 1 || y >= World.MAP_HEIGHT - 1) {
                continue;
            }
            for (int z = Math.max(0, hall.z - 2); z <= Math.min(World.MAP_DEPTH - 1, hall.z + 2); z++) {
                // Outdoors: a spot under the hall's roof had the crowd gather
                // inside the walls (rework play, 2026-10-30)
                if (LivingEntity.isCellAllowed(x, y, z) && World.getCell(x, y, z).getAstarZoneID() != -1 && !Weather.isUnderRoof(x, y, z)) {
                    return new Point3DShort((short) x, (short) y, (short) z);
                }
            }
        }
        return null;
    }

    /**
     * @return a cell for one more of the crowd, where one can stand, not under
     *         a roof and with no one else of the crowd on it (a third entry in
     *         a gatherer's record is the cell it stands on): within two of the
     *         spot, farther out once those are taken, or the spot itself when
     *         no cell is free within CROWD_REACH
     */
    private static Point3DShort near(Point3DShort spot) {
        HashSet<Integer> hsTaken = new HashSet<Integer>();
        for (int[] p : alProtesters) {
            if (p[1] == GO_GATHER) {
                hsTaken.add(p[2]);
            }
        }
        int iZone = World.getCell(spot).getAstarZoneID();
        for (int r = 2; r <= CROWD_REACH; r++) {
            ArrayList<Point3DShort> alFree = new ArrayList<Point3DShort>();
            for (int x = Math.max(0, spot.x - r); x <= Math.min(World.MAP_WIDTH - 1, spot.x + r); x++) {
                for (int y = Math.max(0, spot.y - r); y <= Math.min(World.MAP_HEIGHT - 1, spot.y + r); y++) {
                    if (!hsTaken.contains(cellKey(x, y, spot.z)) && LivingEntity.isCellAllowed(x, y, spot.z)
                            && World.getCell(x, y, spot.z).getAstarZoneID() == iZone && !Weather.isUnderRoof(x, y, spot.z)) {
                        alFree.add(new Point3DShort((short) x, (short) y, spot.z));
                    }
                }
            }
            if (!alFree.isEmpty()) {
                return alFree.get(Utils.getRandomBetween(0, alFree.size() - 1));
            }
        }
        return new Point3DShort(spot.x, spot.y, spot.z);
    }

    /**
     * @return a cell beside the item where one can stand, the one nearest the
     *         living (any, with no living), or null
     */
    private static Point3DShort nextTo(Item item, LivingEntity le) {
        Point3DShort best = null;
        int iBest = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = item.getX() + dx;
                int y = item.getY() + dy;
                int z = item.getZ();
                if ((dx == 0 && dy == 0) || x < 0 || y < 0 || x >= World.MAP_WIDTH || y >= World.MAP_HEIGHT || !LivingEntity.isCellAllowed(x, y, z)) {
                    continue;
                }
                int d = le == null ? 0 : Math.abs(x - le.getX()) + Math.abs(y - le.getY());
                if (d < iBest) {
                    iBest = d;
                    best = new Point3DShort((short) x, (short) y, (short) z);
                }
            }
        }
        return best;
    }

    /**
     * On the target's own level: one a level up beside it stood on the hill
     * outside the hall or over its roof
     */
    private static boolean isNextTo(LivingEntity le, Item item) {
        return Math.abs(le.getX() - item.getX()) <= 1 && Math.abs(le.getY() - item.getY()) <= 1 && le.getZ() == item.getZ();
    }

    /**
     * @return cells between two points on the map (the larger of the two
     *         sideways distances), or a large number on different levels
     *         more than one apart
     */
    private static int distance(Point3DShort a, Point3DShort b) {
        if (Math.abs(a.z - b.z) > 1) {
            return Integer.MAX_VALUE / 2;
        }
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
    }

    private static String slogan(LivingEntity le) {
        return Messages.getString("DataCenter.slogan." + le.getIniHeader()); //$NON-NLS-1$
    }

    private static void say(String sKey, ColorGL color, LivingEntity le) {
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString(sKey), color, le == null ? null : new Point3DShort(le.getX(), le.getY(), le.getZ()));
    }

    static int cellKey(int x, int y, int z) {
        return (z * World.MAP_HEIGHT + y) * World.MAP_WIDTH + x;
    }

    private static int[] cellOf(int iKey) {
        int iPlane = World.MAP_WIDTH * World.MAP_HEIGHT;
        return new int[] {iKey % World.MAP_WIDTH, (iKey / World.MAP_WIDTH) % World.MAP_HEIGHT, iKey / iPlane};
    }
}
