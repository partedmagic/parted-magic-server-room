package xaos.datacenter;

import java.util.ArrayList;

import xaos.main.Game;
import xaos.panels.MessagesPanel;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;

/**
 * The mystery server (Patrick, 2026-11-04, from the list of data center
 * cliches): an old box in the starting kit with a note on it that says DO NOT
 * TURN OFF.  Nobody knows what it does.  It uses power and pays nothing, and
 * the crew's power and the bill both feel it.
 *
 * A technician can pull it (its job in actions.xml; it leaves a box of spare
 * parts).  However it goes, pulled or carried off by thieves, the dice decide
 * what it was doing: nothing, half the healthy racks stop booting, or it takes
 * DNS down with it.  In a tutorial it was doing nothing.
 *
 * Nothing is saved: the item in the world is the state, and the first look at
 * a game just made or loaded only notes whether it is there.
 */
final class OldServer {

    static final String ITEM = "dcoldserver"; //$NON-NLS-1$

    private static final int NOTHING_PCT = 50;
    private static final int RACKS_PCT = 35;
    private static final String RACK_THAT_WONT_BOOT = "dcrackboot"; //$NON-NLS-1$

    private static boolean here;
    private static boolean wasHere;

    private OldServer() {
    }

    static void reset() {
        here = false;
        wasHere = false;
    }

    static void startLook() {
        here = false;
    }

    static void note() {
        here = true;
    }

    /**
     * Every turn, after updateHall
     */
    static void nextTurn(ArrayList<Item> alHallRacks, boolean bTutorial) {
        if (wasHere && !here) {
            gone(alHallRacks, bTutorial);
        }
        wasHere = here;
    }

    private static void gone(ArrayList<Item> alHallRacks, boolean bTutorial) {
        int iRoll = bTutorial ? 1 : Utils.getRandomBetween(1, 100);
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " old server gone, roll " + iRoll); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (iRoll > NOTHING_PCT + RACKS_PCT && Network.hasWorkingDns()) {
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.oldServerDns"), ColorGL.RED); //$NON-NLS-1$
            Network.startDns();
            return;
        }
        if (iRoll > NOTHING_PCT && stopHalfTheRacks(alHallRacks)) {
            return;
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.oldServerNothing"), ColorGL.GREEN); //$NON-NLS-1$
    }

    /**
     * Every second healthy rack, in install order, the first of them too
     *
     * @return whether any stopped
     */
    private static boolean stopHalfTheRacks(ArrayList<Item> alHallRacks) {
        ArrayList<Item> alHealthy = new ArrayList<Item>();
        for (Item rack : alHallRacks) {
            ItemManagerItem imi = ItemManager.getItem(rack.getIniHeader());
            if (imi != null && imi.getDcEventPCT() > 0) {
                alHealthy.add(rack);
            }
        }
        Point3DShort p3d = null;
        for (int i = 0; i < alHealthy.size(); i += 2) {
            Point3DShort p3dRack = DataCenter.replaceRack(alHealthy.get(i), RACK_THAT_WONT_BOOT);
            p3d = p3d == null ? p3dRack : p3d;
        }
        if (p3d == null) {
            return false;
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.oldServerRacks"), ColorGL.RED, p3d); //$NON-NLS-1$
        return true;
    }
}
