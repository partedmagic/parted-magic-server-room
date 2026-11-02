package xaos.datacenter;

import java.util.ArrayList;

import xaos.actions.Action;
import xaos.actions.QueueItem;
import xaos.main.Game;
import xaos.main.World;
import xaos.tasks.Task;
import xaos.tasks.TaskManager;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;

/**
 * Roofs are laid as in Towns (Patrick, 2026-10-31: "in towns you needed a
 * latter to get to the roof as well"): the crew climbs a ladder and lays the
 * roof from its own level, then from the roof already laid
 * (Task.getPlacingPoints).  A roof job nobody can reach waits in the queue
 * (TaskManager, Task.hasRoofAccess).
 *
 * Every half hour this works out whether roof jobs are queued, none of them
 * can be reached and nobody is laying a piece, so the orange row can say the
 * roof waits for a ladder instead of nothing happening.  Nothing is saved.
 */
public final class Roofs {

    private static final int CHECK_TURNS = World.TIME_MODIFIER_HOUR / 2;

    private static int turns;
    private static boolean waitingForLadder;

    private Roofs() {
    }

    static void reset() {
        turns = 0;
        waitingForLadder = false;
    }

    static void nextTurn() {
        if (++turns < CHECK_TURNS) {
            return;
        }
        turns = 0;
        waitingForLadder = isWaitingForLadder();
    }

    private static boolean isWaitingForLadder() {
        TaskManager taskManager = Game.getWorld().getTaskManager();
        int iQueued = 0;
        ArrayList<ArrayList<Action>> alLists = new ArrayList<ArrayList<Action>>();
        alLists.add(taskManager.getCustomActions());
        alLists.add(taskManager.getCustomActionsTemp());
        alLists.add(taskManager.getCustomActionsWait());
        for (ArrayList<Action> alActions : alLists) {
            for (int i = 0; i < alActions.size(); i++) {
                Action action = alActions.get(i);
                if (!isRoofJob(action)) {
                    continue;
                }
                Point3DShort p3d = action.getDestinationPoint();
                if (Task.hasRoofAccess(p3d.x, p3d.y, p3d.z)) {
                    return false;
                }
                iQueued++;
            }
        }
        if (iQueued == 0) {
            return false;
        }
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen && isRoofJob(((Citizen) le).getCurrentCustomAction())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRoofJob(Action action) {
        if (action == null || action.getDestinationPoint() == null || action.getQueue() == null) {
            return false;
        }
        for (QueueItem queueItem : action.getQueue()) {
            if (queueItem.getType() == QueueItem.TYPE_CREATE_ITEM) {
                ItemManagerItem imi = ItemManager.getItem(queueItem.getValue());
                return imi != null && imi.isDcRoof();
            }
        }
        return false;
    }

    /**
     * @return the orange row's part, or null
     */
    static String getStatus() {
        return waitingForLadder ? Messages.getString("DataCenter.statusRoofLadder") : null; //$NON-NLS-1$
    }
}
