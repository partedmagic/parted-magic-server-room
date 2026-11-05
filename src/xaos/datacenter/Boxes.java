package xaos.datacenter;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;

import xaos.actions.Action;
import xaos.actions.ActionManager;
import xaos.actions.ActionManagerItem;
import xaos.actions.QueueItem;
import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tasks.TaskManager;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.UtilsIniHeaders;
import xaos.utils.UtilsString;

/**
 * Boxed gear (Patrick, 2026-10-31: "I have boxed cooling unit and they won't
 * use it").  A box lies where it was dropped until the player orders its
 * install job from the build menu, and an install order waits, silently, for
 * a box that is not there: his save had a boxed cooling unit on the ground
 * and an order for a small generator, which he had no box for.
 *
 * The map tooltip of a box names its build menu entry (getInstallHint), and
 * every half hour this works out which queued jobs wait for hardware nobody
 * has, so the orange row can say what to buy from the supplier truck.
 * Nothing is saved.
 */
public final class Boxes {

    private static final int CHECK_TURNS = World.TIME_MODIFIER_HOUR / 2;
    private static final String HARDWARE = "hardware"; //$NON-NLS-1$
    private static final String BOX_SUFFIX = "kit"; //$NON-NLS-1$

    private static int turns;
    private static String missing;
    private static int unreachable;
    private static final HashSet<Long> hsWarned = new HashSet<Long>();
    // A box's ini header to the name of the job that installs it
    private static HashMap<String, String> hmInstallJobs;

    private Boxes() {
    }

    static void reset() {
        turns = 0;
        missing = null;
        unreachable = 0;
        hsWarned.clear();
    }

    static void nextTurn() {
        if (++turns < CHECK_TURNS) {
            return;
        }
        turns = 0;
        missing = findMissing();
        unreachable = findUnreachable();
    }

    /**
     * Queued place orders nobody on the crew can walk to: a wall, a river or a
     * roof cut the spot off (2026-11-04).  Each gets one log line that jumps to
     * it.  Roof orders are Roofs' business: they wait for a ladder.
     *
     * @return how many there are
     */
    private static int findUnreachable() {
        HashSet<Integer> hsZones = new HashSet<Integer>();
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le != null) {
                hsZones.add(Integer.valueOf(World.getCell(le.getCoordinates()).getAstarZoneID()));
            }
        }
        if (hsZones.isEmpty()) {
            return 0;
        }
        int iCount = 0;
        HashSet<Long> hsNow = new HashSet<Long>();
        TaskManager taskManager = Game.getWorld().getTaskManager();
        ArrayList<ArrayList<Action>> alLists = new ArrayList<ArrayList<Action>>();
        alLists.add(taskManager.getCustomActions());
        alLists.add(taskManager.getCustomActionsTemp());
        alLists.add(taskManager.getCustomActionsWait());
        for (ArrayList<Action> alActions : alLists) {
            for (int i = 0; i < alActions.size(); i++) {
                Action action = alActions.get(i);
                Point3DShort p3d = action.getDestinationPoint();
                ActionManagerItem ami = ActionManager.getItem(action.getId());
                if (p3d == null || ami == null || ami.getGeneratedItem() == null || isRoof(ami) || canReach(p3d, hsZones)) {
                    continue;
                }
                iCount++;
                Long lKey = Long.valueOf(((long) p3d.z << 40) | ((long) p3d.x << 20) | p3d.y);
                hsNow.add(lKey);
                if (!hsWarned.contains(lKey)) {
                    if (Game.isHeadless()) {
                        System.out.println("[DataCenter] " + Game.getWorld().getDate() + " order out of reach " + ami.getId() + " at " + p3d.x + "," + p3d.y + "," + p3d.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                    }
                    MessagesPanel.addMessage(MessagesPanel.TYPE_ANNOUNCEMENT, MessageFormat.format(Messages.getString("DataCenter.orderUnreachable"), ami.getName()), ColorGL.ORANGE, p3d); //$NON-NLS-1$
                }
            }
        }
        hsWarned.clear();
        hsWarned.addAll(hsNow);
        return iCount;
    }

    private static boolean isRoof(ActionManagerItem ami) {
        ItemManagerItem imi = ItemManager.getItem(ami.getGeneratedItem());
        return imi != null && imi.isDcRoof();
    }

    /** The spot or a cell touching it is in a walk zone someone on the crew stands in */
    private static boolean canReach(Point3DShort p3d, HashSet<Integer> hsZones) {
        for (int z = Math.max(0, p3d.z - 1); z <= Math.min(World.MAP_DEPTH - 1, p3d.z + 1); z++) {
            for (int x = Math.max(0, p3d.x - 1); x <= Math.min(World.MAP_WIDTH - 1, p3d.x + 1); x++) {
                for (int y = Math.max(0, p3d.y - 1); y <= Math.min(World.MAP_HEIGHT - 1, p3d.y + 1); y++) {
                    if (hsZones.contains(Integer.valueOf(World.getCell(x, y, z).getAstarZoneID()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @return "Orders nobody can reach N" for the orange row, or null
     */
    static String getUnreachableStatus() {
        return unreachable > 0 ? MessageFormat.format(Messages.getString("DataCenter.statusUnreachable"), unreachable) : null; //$NON-NLS-1$
    }

    /**
     * @return the names of the hardware queued jobs wait for and nobody has,
     *         joined, or null
     */
    private static String findMissing() {
        TreeSet<String> tsNames = new TreeSet<String>();
        TaskManager taskManager = Game.getWorld().getTaskManager();
        ArrayList<ArrayList<Action>> alLists = new ArrayList<ArrayList<Action>>();
        alLists.add(taskManager.getCustomActions());
        alLists.add(taskManager.getCustomActionsTemp());
        alLists.add(taskManager.getCustomActionsWait());
        for (ArrayList<Action> alActions : alLists) {
            for (int i = 0; i < alActions.size(); i++) {
                String sName = getMissingPick(ActionManager.getItem(alActions.get(i).getId()));
                if (sName != null) {
                    tsNames.add(sName);
                }
            }
        }
        return tsNames.isEmpty() ? null : String.join(", ", tsNames); //$NON-NLS-1$
    }

    /**
     * @return the name of the hardware the job's first pick needs when none of
     *         its choices lies anywhere on the site, or null
     */
    private static String getMissingPick(ActionManagerItem ami) {
        if (ami == null || ami.getQueue() == null) {
            return null;
        }
        for (QueueItem queueItem : ami.getQueue()) {
            if (queueItem.getType() != QueueItem.TYPE_PICK) {
                continue;
            }
            String sName = null;
            ArrayList<String> alChoices = UtilsString.getArray(queueItem.getValue());
            if (alChoices == null) {
                return null;
            }
            for (String sHeader : alChoices) {
                ItemManagerItem imi = ItemManager.getItem(sHeader);
                // Only what the supplier sells (type hardware): not used
                // drives (hardware.used), which Clone and Rescue make
                if (imi == null || !HARDWARE.equals(imi.getType())) {
                    return null;
                }
                if (Item.getNumItems(UtilsIniHeaders.getIntIniHeader(sHeader), false, Game.getWorld().getRestrictHaulEquippingLevel()) > 0) {
                    return null;
                }
                if (sName == null) {
                    sName = imi.getName();
                }
            }
            return sName;
        }
        return null;
    }

    /**
     * @return "Buy from the supplier: ..." for the orange row, or null
     */
    static String getStatus() {
        return missing == null ? null : MessageFormat.format(Messages.getString("DataCenter.statusBuyBoxes"), missing); //$NON-NLS-1$
    }

    /**
     * @return for a box on the ground, the build menu entry that installs it
     *         ("To install it: build menu, Data center, Install cooling
     *         unit."), or null
     */
    static String getInstallHint(Item item) {
        if (item.isLocked() || !item.getIniHeader().endsWith(BOX_SUFFIX)) {
            return null;
        }
        if (hmInstallJobs == null) {
            hmInstallJobs = new HashMap<String, String>();
            for (ActionManagerItem ami : ActionManager.getAllItems()) {
                if (ami.getGeneratedItem() == null || ami.getQueue() == null || ami.getName() == null) {
                    continue;
                }
                for (QueueItem queueItem : ami.getQueue()) {
                    if (queueItem.getType() == QueueItem.TYPE_PICK) {
                        ArrayList<String> alChoices = UtilsString.getArray(queueItem.getValue());
                        for (String sHeader : alChoices == null ? new ArrayList<String>() : alChoices) {
                            if (sHeader.endsWith(BOX_SUFFIX)) {
                                hmInstallJobs.put(sHeader, ami.getName());
                            }
                        }
                        break;
                    }
                }
            }
        }
        String sJob = hmInstallJobs.get(item.getIniHeader());
        return sJob == null ? null : MessageFormat.format(Messages.getString("DataCenter.hintInstall"), sJob); //$NON-NLS-1$
    }
}
