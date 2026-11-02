package xaos.datacenter;

import java.util.ArrayList;

import xaos.actions.Action;
import xaos.actions.ActionManager;
import xaos.actions.ActionManagerItem;
import xaos.actions.QueueData;
import xaos.main.Game;
import xaos.panels.MainPanel;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;

/**
 * Tomato planters (docs/stakes.md, step 6).
 *
 * A planter grows by aging, as Towns' crops did (items.xml: dcplanter,
 * dcplantergrowing, dcplanterripe).  A ripe one (dcPick) gets its job
 * queued here, as if the player had marked it with an order, so the
 * technicians pick the tomatoes on their own.  The job swaps it for
 * dcplanterpicked, which drops the rest of the tomatoes on the cells beside
 * it and then grows more (dcplantergrowing).
 */
public final class Garden {

    // How often the ripe planters are looked at, in turns (15 minutes)
    private static final int CHECK_TURNS = 30;

    private Garden() {
    }

    /**
     * Every turn, after DataCenter.updateHall has found the ripe planters:
     * now and then a pick job for each.  The task manager drops one that is
     * already queued or under way for the same planter, so nothing needs
     * remembering here, and a saved game keeps its queued jobs.
     */
    static void nextTurn(ArrayList<Item> alRipe) {
        if (alRipe.isEmpty() || Game.getWorld().getTurn() % CHECK_TURNS != 0) {
            return;
        }
        for (Item planter : alRipe) {
            ItemManagerItem imi = ItemManager.getItem(planter.getIniHeader());
            if (imi == null || imi.getActions() == null || imi.getActions().isEmpty()) {
                continue;
            }
            ActionManagerItem ami = ActionManager.getItem(imi.getActions().get(0));
            if (ami == null) {
                continue;
            }
            Action action = new Action(ami.getId());
            action.setEntityID(planter.getID());
            action.setQueue(ami.getQueue());
            action.setQueueData(new QueueData());
            action.setFace(MainPanel.itemBuildFace);
            Game.getWorld().getTaskManager().addCustomAction(action, true, false);
        }
    }
}
