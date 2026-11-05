package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.HashSet;

import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;

/**
 * The two ways a whole site drops off the internet (Patrick, 2026-11-04,
 * picking the backhoe and DNS from the list of data center cliches).  In both
 * the racks keep running and drawing power, and the customers cannot reach
 * them: no pay, and the red cross on every rack (DataCenter.updateHall).
 *
 * The backhoe: from FROM_DAY a dawn may set a fiber cut for that day, and a
 * construction worker digging near the fiber line may cut it too.  The
 * provider fixes it after some hours; nothing here can hurry it.  A second
 * fiber line, bought from the supplier, keeps the site online through it.
 *
 * DNS: from FROM_DAY a dawn may set a DNS failure for that day.  The DNS
 * server is swapped for dcdnsdown, which looks the same and offers Flush DNS,
 * and it stays down until a technician does that.  The log only says it cannot
 * be DNS; after ASK_HOURS a technician asks whether anyone has checked, with a
 * jump to the box, and the box gets the red cross.  The item in the world is
 * the failure's state, so a save keeps it.
 *
 * Neither happens in a tutorial.  A save from before this has no fiber line
 * and no DNS server, so neither happens there either.
 */
public final class Network {

    // dcNet in items.xml
    public static final int NET_FIBER = 1;
    public static final int NET_SECOND_LINE = 2;
    public static final int NET_DNS = 3;
    public static final int NET_DNS_DOWN = 4;

    private static final String DNS_DOWN = "dcdnsdown"; //$NON-NLS-1$

    private static final int FROM_DAY = 6;
    private static final int FIBER_PCT = 10;
    private static final int DNS_PCT = 8;
    // Hours until the provider fixes the line: a backhoe out on the road, and
    // your own crew, who get no priority
    private static final int CUT_HOURS_MIN = 3;
    private static final int CUT_HOURS_MAX = 6;
    private static final int DUG_HOURS_MIN = 4;
    private static final int DUG_HOURS_MAX = 8;
    // A dig this near the fiber line, in cells and levels, hits it one time in
    // DIG_DIE
    private static final int DIG_RADIUS = 12;
    private static final int DIG_LEVELS = 2;
    private static final int DIG_DIE = 50;
    private static final int ASK_HOURS = 2;

    // Saved: the turn of the day a cut starts (-1 when none is coming) and the
    // turns left of one that is on; the same for DNS, with the turns it has
    // been down
    private static int fiberStart = -1;
    private static int fiberLeft;
    private static int dnsStart = -1;
    private static int dnsTurns;

    // At the last look (DataCenter.updateHall)
    private static Item fiberBox;
    private static Item dnsBox;
    private static boolean secondLine;
    private static boolean dnsDown;
    private static boolean wasDnsDown;

    private Network() {
    }

    static void reset() {
        fiberStart = -1;
        fiberLeft = 0;
        dnsStart = -1;
        dnsTurns = 0;
        fiberBox = null;
        dnsBox = null;
        secondLine = false;
        dnsDown = false;
        wasDnsDown = false;
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(fiberStart);
        out.writeInt(fiberLeft);
        out.writeInt(dnsStart);
        out.writeInt(dnsTurns);
    }

    static void readExternal(ObjectInput in) throws IOException {
        fiberStart = in.readInt();
        fiberLeft = in.readInt();
        dnsStart = in.readInt();
        dnsTurns = in.readInt();
    }

    /**
     * Before updateHall's look at the items
     */
    static void startLook() {
        fiberBox = null;
        dnsBox = null;
        secondLine = false;
        dnsDown = false;
    }

    /**
     * An installed fiber line or DNS server, in updateHall's look at the items
     */
    static void note(Item item, int iNet) {
        if (iNet == NET_FIBER) {
            fiberBox = item;
        } else if (iNet == NET_SECOND_LINE) {
            secondLine = true;
        } else {
            dnsBox = item;
            dnsDown = iNet == NET_DNS_DOWN;
        }
    }

    /**
     * After updateHall's look: what wears the red cross, and on a turn the log
     * says when a flush has worked
     */
    static void endLook(HashSet<Integer> hsWreckedGear, boolean bTurn) {
        if (fiberBox != null && isFiberCut()) {
            hsWreckedGear.add(Integer.valueOf(fiberBox.getID()));
        }
        if (dnsBox != null && dnsDown && isAsked()) {
            hsWreckedGear.add(Integer.valueOf(dnsBox.getID()));
        }
        if (bTurn && wasDnsDown && !dnsDown) {
            dnsTurns = 0;
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " DNS flushed"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.dnsFixed"), ColorGL.GREEN); //$NON-NLS-1$
        }
        wasDnsDown = dnsDown;
    }

    /**
     * Every turn, before updateHall: a coming cut or DNS failure starts at its
     * hour, a cut counts down, and a DNS failure counts toward someone asking
     */
    static void nextTurn() {
        if (fiberLeft > 0) {
            fiberLeft--;
            if (fiberLeft == 0) {
                if (Game.isHeadless()) {
                    System.out.println("[DataCenter] " + Game.getWorld().getDate() + " fiber fixed"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.fiberFixed"), ColorGL.GREEN); //$NON-NLS-1$
            }
        } else if (fiberStart >= 0 && Game.getWorld().getTurn() >= fiberStart) {
            fiberStart = -1;
            cut(Messages.getString(secondLine ? "DataCenter.fiberCutCovered" : "DataCenter.fiberCut"), Utils.getRandomBetween(CUT_HOURS_MIN, CUT_HOURS_MAX)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (dnsStart >= 0 && Game.getWorld().getTurn() >= dnsStart) {
            dnsStart = -1;
            startDns();
        }
        if (dnsDown) {
            dnsTurns++;
            if (dnsTurns == ASK_HOURS * World.TIME_MODIFIER_HOUR) {
                ask();
            }
        }
    }

    /**
     * Dawn, from FROM_DAY: a fiber cut and a DNS failure may be set for the day,
     * at a random hour
     */
    static void newDay(boolean bTutorial) {
        if (bTutorial || DataCenter.isSiteLost() || Game.getWorld().getDate().getDaysSinceStart() < FROM_DAY) {
            return;
        }
        if (fiberBox != null && fiberLeft == 0 && fiberStart < 0 && Utils.getRandomBetween(1, 100) <= FIBER_PCT) {
            fiberStart = Utils.getRandomBetween(0, 23) * World.TIME_MODIFIER_HOUR;
        }
        if (dnsBox != null && !dnsDown && dnsStart < 0 && Utils.getRandomBetween(1, 100) <= DNS_PCT) {
            dnsStart = Utils.getRandomBetween(0, 23) * World.TIME_MODIFIER_HOUR;
        }
    }

    /**
     * A cell was just dug out (Citizen): near the fiber line, the shovel may
     * find it
     */
    public static void dugOut(Citizen digger, int x, int y, int z) {
        if (fiberBox == null || isFiberCut() || DataCenter.isTutorial()
                || Math.abs(x - fiberBox.getX()) > DIG_RADIUS || Math.abs(y - fiberBox.getY()) > DIG_RADIUS || Math.abs(z - fiberBox.getZ()) > DIG_LEVELS) {
            return;
        }
        if (Utils.getRandomBetween(1, DIG_DIE) == 1) {
            String sKey = secondLine ? "DataCenter.fiberDugCovered" : "DataCenter.fiberDug"; //$NON-NLS-1$ //$NON-NLS-2$
            cut(MessageFormat.format(Messages.getString(sKey), digger.getCitizenData().getFullName()), Utils.getRandomBetween(DUG_HOURS_MIN, DUG_HOURS_MAX));
        }
    }

    private static void cut(String sMessage, int iHours) {
        fiberLeft = iHours * World.TIME_MODIFIER_HOUR;
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + sMessage + " (" + iHours + " h)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        Point3DShort p3d = fiberBox == null ? null : new Point3DShort(fiberBox.getX(), fiberBox.getY(), fiberBox.getZ());
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, sMessage, secondLine ? ColorGL.ORANGE : ColorGL.RED, p3d);
    }

    /**
     * @return whether an item of this dcNet is one the player cannot remove
     */
    public static boolean isFixture(int iNet) {
        return iNet == NET_FIBER || iNet == NET_DNS || iNet == NET_DNS_DOWN;
    }

    static boolean hasWorkingDns() {
        return dnsBox != null && !dnsDown;
    }

    /**
     * DNS goes down, if there is a working DNS server to go down
     */
    static void startDns() {
        if (!hasWorkingDns() || DataCenter.replaceRack(dnsBox, DNS_DOWN) == null) {
            return;
        }
        // Down from now: the next look at the items finds the new box
        dnsBox = null;
        dnsDown = true;
        wasDnsDown = true;
        dnsTurns = 0;
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " DNS down"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.dnsDown"), ColorGL.RED); //$NON-NLS-1$
    }

    /**
     * A technician thinks of it, named in the log, with a jump to the box
     */
    private static void ask() {
        String sName = null;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen && !Crew.isBuilder(le) && !Crew.isGuard(le)) {
                sName = ((Citizen) le).getCitizenData().getFullName();
                break;
            }
        }
        if (sName == null || dnsBox == null) {
            return;
        }
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + sName + " asks about DNS"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.dnsAsk"), sName), ColorGL.ORANGE, new Point3DShort(dnsBox.getX(), dnsBox.getY(), dnsBox.getZ())); //$NON-NLS-1$
    }

    private static boolean isAsked() {
        return dnsTurns >= ASK_HOURS * World.TIME_MODIFIER_HOUR;
    }

    private static boolean isFiberCut() {
        return fiberLeft > 0;
    }

    /**
     * @return true while customers cannot reach the racks
     */
    static boolean isOffline() {
        return dnsDown || (isFiberCut() && !secondLine);
    }

    /**
     * @return the tooltip's line for a rack that is offline
     */
    static String getRackHint() {
        return Messages.getString(dnsDown ? "DataCenter.hintDns" : "DataCenter.hintFiber"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return the tooltip's line for a fiber line that is cut, or null
     */
    static String getBoxHint(int iNet) {
        return iNet == NET_FIBER && isFiberCut() ? Messages.getString("DataCenter.hintFiberBox") : null; //$NON-NLS-1$
    }

    /**
     * @return the orange row's part for a fiber cut, or null
     */
    static String getFiberStatus() {
        if (!isFiberCut()) {
            return null;
        }
        int iHours = (fiberLeft + World.TIME_MODIFIER_HOUR - 1) / World.TIME_MODIFIER_HOUR;
        return MessageFormat.format(Messages.getString(secondLine ? "DataCenter.statusFiberCovered" : "DataCenter.statusFiber"), iHours); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return the orange row's part for a DNS failure, or null
     */
    static String getDnsStatus() {
        return dnsDown ? Messages.getString(isAsked() ? "DataCenter.statusDnsAsk" : "DataCenter.statusDns") : null; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
