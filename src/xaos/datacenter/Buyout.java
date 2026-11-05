package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

import xaos.campaign.MissionData;
import xaos.main.Game;
import xaos.main.World;
import xaos.panels.CommandPanel;
import xaos.panels.MessagesPanel;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;

/**
 * The ending (Patrick, 2026-11-04: "the person with the biggest buyout is #1
 * on the leaderboard").  Once a site is big and well run, Ellen Tusk calls at
 * dawn with an offer: about a year of what the site earns, more for a good
 * reputation and good uptime.  It does not count the coins on hand, so nobody
 * gains by sitting on money, and it fades a little each day from FADE_FROM_DAY,
 * so nobody gains by leaving the game running for weeks.
 *
 * Keep the site and the next call comes a week later, at whatever the site is
 * worth by then.  Sell, and every rack becomes a rack to wipe (Erase, as after
 * a move-out); WIPE_TURNS later, or when the last one is wiped, the sale closes
 * with MISSED_PCT off for each rack not wiped, the game is over and the price
 * goes on the player's list of best buyouts (BuyoutList).  A site lost first
 * scores nothing.  Never in a tutorial.
 *
 * The numbers come from the balance bot (docs/rules.md): a careful player
 * first meets the conditions around day 50, for about 50,000 coins.
 *
 * Saved with the game (Game.SAVEGAME_BUYOUT).
 */
public final class Buyout {

    public static final String BUYER = "Ellen Tusk"; //$NON-NLS-1$
    public static final int MISSED_PCT = 5;

    private static final int OFFER_CUSTOMERS = 12;
    private static final int OFFER_REPUTATION = 40;
    private static final int OFFER_UPTIME_PCT = 80;
    private static final int OFFER_UPTIME_DAYS = 5;
    private static final int CALL_AGAIN_DAYS = 7;
    private static final int WINDOW_DAYS = 10;
    private static final int DAYS_A_YEAR = 365;
    private static final int REPUTATION_BASE = 50;
    private static final int FADE_FROM_DAY = 120;
    private static final double FADE_A_DAY = 0.99;
    private static final int PRICE_ROUNDING = 100;
    private static final int WIPE_TURNS = 2 * World.TIME_MODIFIER_DAY;
    private static final int MAX_MISSED_PCT = 75;
    private static final int REMINDER_TURNS = 6 * World.TIME_MODIFIER_HOUR;

    // The last WINDOW_DAYS dawns, going round: what the day left after the
    // bills, and how much of its pay the racks earned
    private static final int[] netDays = new int[WINDOW_DAYS];
    private static final int[] uptimeDays = new int[WINDOW_DAYS];
    private static int daysSeen;

    private static int nextCallDay;
    private static int lastOffer;
    private static int lastOfferDay = -1;
    private static int estimate;
    private static boolean offerOpen;

    private static boolean sold;
    private static int salePrice;
    private static int wipeTurnsLeft;
    private static int wiped;
    private static int gone;
    private static int drivesErased;
    // Racks still to wipe, by cell key
    private static final HashSet<Integer> hsToWipe = new HashSet<Integer>();

    private Buyout() {
    }

    static void reset() {
        for (int i = 0; i < WINDOW_DAYS; i++) {
            netDays[i] = 0;
            uptimeDays[i] = 0;
        }
        daysSeen = 0;
        nextCallDay = 0;
        lastOffer = 0;
        lastOfferDay = -1;
        estimate = 0;
        offerOpen = false;
        sold = false;
        salePrice = 0;
        wipeTurnsLeft = 0;
        wiped = 0;
        gone = 0;
        drivesErased = 0;
        hsToWipe.clear();
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        for (int i = 0; i < WINDOW_DAYS; i++) {
            out.writeInt(netDays[i]);
            out.writeInt(uptimeDays[i]);
        }
        out.writeInt(daysSeen);
        out.writeInt(nextCallDay);
        out.writeInt(lastOffer);
        out.writeInt(lastOfferDay);
        out.writeInt(estimate);
        out.writeBoolean(offerOpen);
        out.writeBoolean(sold);
        out.writeInt(salePrice);
        out.writeInt(wipeTurnsLeft);
        out.writeInt(wiped);
        out.writeInt(gone);
        out.writeInt(drivesErased);
        out.writeInt(hsToWipe.size());
        for (Integer iKey : hsToWipe) {
            out.writeInt(iKey.intValue());
        }
    }

    static void readExternal(ObjectInput in) throws IOException {
        for (int i = 0; i < WINDOW_DAYS; i++) {
            netDays[i] = in.readInt();
            uptimeDays[i] = in.readInt();
        }
        daysSeen = in.readInt();
        nextCallDay = in.readInt();
        lastOffer = in.readInt();
        lastOfferDay = in.readInt();
        estimate = in.readInt();
        offerOpen = in.readBoolean();
        sold = in.readBoolean();
        salePrice = in.readInt();
        wipeTurnsLeft = in.readInt();
        wiped = in.readInt();
        gone = in.readInt();
        drivesErased = in.readInt();
        for (int i = in.readInt(); i > 0; i--) {
            hsToWipe.add(Integer.valueOf(in.readInt()));
        }
    }

    /**
     * Dawn, once the day's pay and bills are known
     */
    static void noteDay(int iPaid, int iBills, int iUptimePct) {
        int i = daysSeen % WINDOW_DAYS;
        netDays[i] = iPaid - iBills;
        uptimeDays[i] = iUptimePct;
        daysSeen++;
    }

    /**
     * @return the average uptime of the last iDays dawns seen, in percent
     */
    private static int getUptime(int iDays) {
        int iSeen = Math.min(Math.min(daysSeen, iDays), WINDOW_DAYS);
        if (iSeen == 0) {
            return 0;
        }
        int iSum = 0;
        for (int i = 1; i <= iSeen; i++) {
            iSum += uptimeDays[(daysSeen - i) % WINDOW_DAYS];
        }
        return iSum / iSeen;
    }

    /**
     * @return what the site would fetch today, rounded, 0 when it earns nothing
     */
    private static int getPrice(int iReputation) {
        int iSeen = Math.min(daysSeen, WINDOW_DAYS);
        if (iSeen == 0) {
            return 0;
        }
        long lNet = 0;
        for (int i = 0; i < iSeen; i++) {
            lNet += netDays[i];
        }
        if (lNet <= 0) {
            return 0;
        }
        double dUptime = getUptime(WINDOW_DAYS) / 100.0;
        double dPrice = (double) lNet / iSeen * DAYS_A_YEAR * (REPUTATION_BASE + iReputation) / 100.0 * dUptime * dUptime;
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        if (iDay > FADE_FROM_DAY) {
            dPrice *= Math.pow(FADE_A_DAY, iDay - FADE_FROM_DAY);
        }
        return roundPrice(dPrice);
    }

    private static int roundPrice(double dPrice) {
        return (int) Math.round(dPrice / PRICE_ROUNDING) * PRICE_ROUNDING;
    }

    /**
     * Dawn, last of all: the call, when the site is big and well run and the
     * last call is a week old.  It goes on through a day the site falls short:
     * the buyer waits for it to recover.
     */
    static void newDay(int iCustomers, int iReputation, boolean bTutorial) {
        if (bTutorial || sold) {
            return;
        }
        estimate = getPrice(iReputation);
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " buyout worth " + estimate + " (uptime " + getUptime(WINDOW_DAYS) + ", reputation " + iReputation + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        if (iDay < nextCallDay || iCustomers < OFFER_CUSTOMERS || iReputation < OFFER_REPUTATION || getUptime(OFFER_UPTIME_DAYS) < OFFER_UPTIME_PCT || estimate <= 0) {
            return;
        }
        lastOffer = estimate;
        lastOfferDay = iDay;
        nextCallDay = iDay + CALL_AGAIN_DAYS;
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " offer " + lastOffer); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        offerOpen = true;
        CommandPanel.showOffer(BUYER, lastOffer, MISSED_PCT);
    }

    /**
     * Keep the site (CommandPanel's panel): the next call is a week on
     */
    public static void keep() {
        offerOpen = false;
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.buyoutKept"), BUYER), ColorGL.ORANGE); //$NON-NLS-1$
    }

    /**
     * Sell (CommandPanel's panel, or a test bot): every rack is now a rack to
     * wipe and the customers have gone.  The sale closes in nextTurn.
     */
    public static void sell() {
        if (sold || lastOffer <= 0) {
            return;
        }
        offerOpen = false;
        sold = true;
        salePrice = lastOffer;
        wipeTurnsLeft = WIPE_TURNS;
        wiped = 0;
        gone = 0;
        hsToWipe.clear();
        hsToWipe.addAll(DataCenter.vacateRacks());
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " sold for " + salePrice + ", racks to wipe " + hsToWipe.size()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.buyoutSold"), BUYER, salePrice, MISSED_PCT), ColorGL.ORANGE); //$NON-NLS-1$
    }

    /**
     * Every turn: a call the save held open comes back up; during the sale, the
     * racks wiped or gone since the last look, and the end of it
     */
    static void nextTurn() {
        if (offerOpen && !sold && !Game.isHeadless() && !CommandPanel.isOfferShowing()) {
            CommandPanel.showOffer(BUYER, lastOffer, MISSED_PCT);
        }
        if (!sold || DataCenter.isSiteLost()) {
            return;
        }
        ArrayList<Integer> alDone = new ArrayList<Integer>();
        for (Integer iKey : hsToWipe) {
            int[] c = DataCenter.cellOf(iKey.intValue());
            Item item = World.getCell(c[0], c[1], c[2]).getItem();
            if (item != null && MoveOuts.VACATED_RACK.equals(item.getIniHeader())) {
                continue;
            }
            alDone.add(iKey);
            ItemManagerItem imi = item == null ? null : ItemManager.getItem(item.getIniHeader());
            if (imi != null && imi.getDcIncome() > 0) {
                wiped++;
                drivesErased++;
            } else {
                gone++;
            }
        }
        hsToWipe.removeAll(alDone);
        wipeTurnsLeft--;
        if (hsToWipe.isEmpty() || wipeTurnsLeft <= 0) {
            closeSale();
        } else if (wipeTurnsLeft % REMINDER_TURNS == 0) {
            remind();
        }
    }

    /**
     * A log line every few hours that jumps to a rack still to wipe: the last
     * one can be hard to find
     */
    private static void remind() {
        int[] c = DataCenter.cellOf(Collections.min(hsToWipe).intValue());
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.buyoutLeft"), hsToWipe.size()), ColorGL.ORANGE, new Point3DShort((short) c[0], (short) c[1], (short) c[2])); //$NON-NLS-1$
    }

    private static void closeSale() {
        int iMissed = hsToWipe.size() + gone;
        int iPct = Math.min(MAX_MISSED_PCT, iMissed * MISSED_PCT);
        int iPrice = roundPrice(salePrice * (100 - iPct) / 100.0);
        int iDays = Game.getWorld().getDate().getDaysSinceStart();
        MissionData mission = Game.getCurrentMissionData();
        int iRank = BuyoutList.add(iPrice, iDays, Game.getSavegameName(), mission == null ? "" : mission.getName()); //$NON-NLS-1$
        DataCenter.endSite();
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " sale closed at " + iPrice + ", offer " + salePrice + ", wiped " + wiped + ", missed " + iMissed + ", drives erased " + drivesErased + ", place " + iRank); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            return;
        }
        CommandPanel.showSold(BUYER, iPrice, salePrice, iMissed, iPct, iDays, drivesErased, iRank, mission == null ? "" : mission.getName()); //$NON-NLS-1$
    }

    /**
     * A drive erased at an erase station or a rack wiped after a move-out, for
     * the last screen
     */
    public static void noteDriveErased() {
        drivesErased++;
    }

    public static boolean isSold() {
        return sold;
    }

    /**
     * @return the last offer, 0 before the first call
     */
    public static int getLastOffer() {
        return lastOffer;
    }

    /**
     * @return the day of the last offer, -1 before the first call
     */
    public static int getLastOfferDay() {
        return lastOfferDay;
    }

    /**
     * @return the second status row's part once the buyer has called, or null
     */
    static String getStatusBusiness() {
        if (lastOffer <= 0 || sold) {
            return null;
        }
        return MessageFormat.format(Messages.getString("DataCenter.buyoutStatus"), estimate); //$NON-NLS-1$
    }

    /**
     * @return the orange row's part during the sale, or null
     */
    static String getStatusProblems() {
        if (!sold || DataCenter.isSiteLost()) {
            return null;
        }
        return MessageFormat.format(Messages.getString("DataCenter.buyoutWipe"), hsToWipe.size(), (wipeTurnsLeft + World.TIME_MODIFIER_HOUR - 1) / World.TIME_MODIFIER_HOUR); //$NON-NLS-1$
    }
}
