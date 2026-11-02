package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tiles.entities.items.Item;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;

/**
 * Customers moving out, and the erase that follows (the rework of
 * 2026-10-30, Patrick: "our main program in parted magic is the erase",
 * "Data centers do erase drives").
 *
 * From day 3, at dawn, a customer whose rack runs healthy has MOVE_OUT_PCT
 * in 100 of its contract ending (one move-out a dawn at most).  Its rack is
 * swapped for VACATED_RACK, the same rack still holding the old customer's
 * data: it takes no power, earns nothing and cannot take a new customer.
 * The Erase tool (Parted Magic button, the job qeraserack) sends a
 * technician to wipe it with Parted Magic Erase, which puts a healthy empty
 * rack back.  Wiped by the next dawn, the old customer pays ERASE_FEE for
 * the erase certificate and says so (REPUTATION_CERTIFICATE); later, it
 * pays nothing, and at that dawn it complains (REPUTATION_NO_CERTIFICATE,
 * once).  A wiped rack signs a new
 * customer as any empty rack does (DataCenter.signCustomers).
 *
 * The racks waiting to be wiped are kept by cell, with the day their
 * certificate is due and whether the complaint has come, and saved with the
 * game (Game.SAVEGAME_REWORK).  None in a tutorial.
 */
public final class MoveOuts {

    static final String VACATED_RACK = "dcrackvacated"; //$NON-NLS-1$
    private static final String HEALTHY_RACK = "dcrack"; //$NON-NLS-1$
    private static final int FROM_DAY = 3;
    private static final int MOVE_OUT_PCT = 6;
    private static final int ERASE_FEE = 40;
    private static final int REPUTATION_NO_CERTIFICATE = 6;
    private static final int REPUTATION_CERTIFICATE = 1;

    // Racks to wipe, by cell key: the day the certificate is due, and 1 once
    // the old customer has complained
    private static final HashMap<Integer, int[]> hmToWipe = new HashMap<Integer, int[]>();

    private MoveOuts() {
    }

    static void reset() {
        hmToWipe.clear();
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(hmToWipe.size());
        for (Map.Entry<Integer, int[]> entry : hmToWipe.entrySet()) {
            out.writeInt(entry.getKey().intValue());
            out.writeInt(entry.getValue()[0]);
            out.writeInt(entry.getValue()[1]);
        }
    }

    static void readExternal(ObjectInput in) throws IOException {
        for (int n = in.readInt(); n > 0; n--) {
            int iKey = in.readInt();
            hmToWipe.put(Integer.valueOf(iKey), new int[] {in.readInt(), in.readInt()});
        }
    }

    /**
     * Dawn: the complaints for certificates now late, then maybe a move-out
     * among the customers' running racks (in install order, so a seeded
     * game repeats)
     *
     * @param alRunningRackIDs the racks that ran last turn
     * @param hsEmptyRacks the racks with no customer, by cell key
     */
    static void newDay(ArrayList<Integer> alRunningRackIDs, HashSet<Integer> hsEmptyRacks, boolean bTutorial) {
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        for (Map.Entry<Integer, int[]> entry : hmToWipe.entrySet()) {
            int[] a = entry.getValue();
            if (a[1] == 0 && iDay >= a[0]) {
                a[1] = 1;
                int iReputation = DataCenter.newsHit(REPUTATION_NO_CERTIFICATE);
                if (Game.isHeadless()) {
                    System.out.println("[DataCenter] " + Game.getWorld().getDate() + " erase certificate late, reputation " + iReputation); //$NON-NLS-1$ //$NON-NLS-2$
                }
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.eraseLate"), iReputation), ColorGL.RED, point(entry.getKey().intValue())); //$NON-NLS-1$
            }
        }
        if (bTutorial || iDay < FROM_DAY) {
            return;
        }
        for (Integer iID : alRunningRackIDs) {
            Item rack = Item.getItemByID(iID.intValue());
            if (rack == null || !HEALTHY_RACK.equals(rack.getIniHeader()) || !DataCenter.isInstalled(rack)) {
                continue;
            }
            Integer iKey = Integer.valueOf(DataCenter.cellKey(rack));
            if (hsEmptyRacks.contains(iKey) || Utils.getRandomBetween(1, 100) > MOVE_OUT_PCT) {
                continue;
            }
            Point3DShort p3d = DataCenter.replaceRack(rack, VACATED_RACK);
            if (p3d == null) {
                return;
            }
            hmToWipe.put(iKey, new int[] {iDay + 1, 0});
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " customer moved out at " + p3d.x + "," + p3d.y + "," + p3d.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.moveOut"), ERASE_FEE), ColorGL.ORANGE, p3d); //$NON-NLS-1$
            return;
        }
    }

    /**
     * Every turn, before the hall is worked out: a rack to wipe that no longer
     * holds the old data has been wiped (the Erase job swapped it).  Its cell
     * is marked empty first, so the hall never counts the old customer again.
     *
     * @param hsEmptyRacks the racks with no customer, by cell key
     */
    static void nextTurn(HashSet<Integer> hsEmptyRacks) {
        if (hmToWipe.isEmpty()) {
            return;
        }
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        ArrayList<Integer> alDone = new ArrayList<Integer>();
        for (Map.Entry<Integer, int[]> entry : hmToWipe.entrySet()) {
            int iKey = entry.getKey().intValue();
            Item item = itemAt(iKey);
            if (item != null && VACATED_RACK.equals(item.getIniHeader())) {
                continue;
            }
            alDone.add(entry.getKey());
            if (item == null) {
                // The rack was taken away with the old data on it
                continue;
            }
            hsEmptyRacks.add(entry.getKey());
            boolean bOnTime = iDay < entry.getValue()[0];
            int iReputation = 0;
            if (bOnTime) {
                Game.getWorld().setCoins(Game.getWorld().getCoins() + ERASE_FEE);
                // A pleased old customer says so: reputation rose only after
                // a day no rack was down, which a played site rarely had, and
                // sat near 30 from day 15 (rework play, 2026-10-30)
                iReputation = DataCenter.newsHit(-REPUTATION_CERTIFICATE);
            }
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " rack wiped" + (bOnTime ? ", fee " + ERASE_FEE : ", late")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, bOnTime ? MessageFormat.format(Messages.getString("DataCenter.eraseCertificate"), ERASE_FEE, iReputation) : Messages.getString("DataCenter.eraseLateDone"), bOnTime ? ColorGL.GREEN : ColorGL.ORANGE, point(iKey)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        hmToWipe.keySet().removeAll(alDone);
    }

    /**
     * @return the cells of the racks waiting to be wiped.  VACATED_RACK earns
     *         nothing, so the hall does not count it as a rack, but the cell
     *         is still a rack's: once wiped, the rack there is not a new one
     *         (DataCenter.noteNewRacks)
     */
    static java.util.Set<Integer> getCells() {
        return hmToWipe.keySet();
    }

    /**
     * @return the orange row's part for the racks waiting to be wiped, or
     *         null
     */
    static String getStatus() {
        if (hmToWipe.isEmpty()) {
            return null;
        }
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        int iLate = 0;
        for (int[] a : hmToWipe.values()) {
            if (iDay >= a[0]) {
                iLate++;
            }
        }
        return MessageFormat.format(Messages.getString(iLate > 0 ? "DataCenter.statusToWipeLate" : "DataCenter.statusToWipe"), hmToWipe.size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Item itemAt(int iKey) {
        int[] c = DataCenter.cellOf(iKey);
        return World.getCell(c[0], c[1], c[2]).getItem();
    }

    private static Point3DShort point(int iKey) {
        int[] c = DataCenter.cellOf(iKey);
        return new Point3DShort((short) c[0], (short) c[1], (short) c[2]);
    }
}
