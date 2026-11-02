package xaos.datacenter;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeSet;

import xaos.actions.Action;
import xaos.actions.ActionManager;
import xaos.actions.ActionManagerItem;
import xaos.actions.QueueItem;
import xaos.main.Game;
import xaos.main.World;
import xaos.tasks.TaskManager;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.utils.Messages;
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
    // A box's ini header to the name of the job that installs it
    private static HashMap<String, String> hmInstallJobs;

    private Boxes() {
    }

    static void reset() {
        turns = 0;
        missing = null;
    }

    static void nextTurn() {
        if (++turns < CHECK_TURNS) {
            return;
        }
        turns = 0;
        missing = findMissing();
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
