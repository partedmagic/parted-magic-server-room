package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import xaos.actions.Action;
import xaos.actions.QueueItem;
import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tasks.TaskManager;
import xaos.tiles.Cell;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.UtilsIniHeaders;
import xaos.utils.UtilsString;
import xaos.zones.Zone;

/**
 * Building materials on order (the stakes rework of 2026-10-30): concrete
 * and steel come when a build order needs them, not on a truck's visit.
 * This class also keeps the day the supplier truck last came (World sends
 * it every morning).
 *
 * Every quarter of a game hour the queued jobs are read (the task
 * manager's three lists and the technicians' current jobs, whose queues
 * hold only the steps still to do) and each pick of a building material
 * (SUPPLY_TYPE) counts as one needed.  What the loose stock (unlocked,
 * containers included) does not cover is delivered at once, beside the
 * loading dock, or beside the grid feed while there is none, and paid at
 * the item's value.  Never more than the coins on hand less a day's bills
 * (kept for dawn): what cannot be paid waits, and the log says so once a
 * day.
 *
 * A delivery never lands on a zone (the dock stays clear for the trucks),
 * on a cell with a build order (the order would be dropped as blocked), or
 * beside a machine that puts things out (the vending and coffee machines
 * and the planters make nothing while every cell around them is full).
 */
public final class Supply {

    private static final String SUPPLY_TYPE = "rawmaterials.building"; //$NON-NLS-1$
    private static final String DOCK_ZONE = "zmarket"; //$NON-NLS-1$
    private static final int CHECK_TURNS = World.TIME_MODIFIER_HOUR / 4;
    private static final int MAX_RADIUS = 20;

    // The day the log last said the coins ran short
    private static int noCoinsDay = -1;

    // The day the supplier truck last came (World sends it every morning);
    // saved, so a load does not bring it twice in a day
    private static int supplierDay = -1;

    private Supply() {
    }

    static void reset() {
        noCoinsDay = -1;
        supplierDay = -1;
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(supplierDay);
    }

    static void readExternal(ObjectInput in) throws IOException {
        supplierDay = in.readInt();
    }

    /**
     * @return true when the supplier truck has not come today
     */
    public static boolean isSupplierDue() {
        return supplierDay != Game.getWorld().getDate().getDaysSinceStart();
    }

    public static void supplierSent() {
        supplierDay = Game.getWorld().getDate().getDaysSinceStart();
    }

    static void nextTurn() {
        if (Game.getWorld().getTurn() % CHECK_TURNS != 0 || World.getCitizenIDs().isEmpty()) {
            return;
        }
        LinkedHashMap<String, Integer> hmShort = getShortfall();
        if (hmShort.isEmpty()) {
            return;
        }
        deliver(hmShort);
    }

    /**
     * @return the building materials the queued jobs still have to pick,
     *         less the loose stock, by item, in the order first needed
     */
    private static LinkedHashMap<String, Integer> getShortfall() {
        LinkedHashMap<String, Integer> hmNeed = new LinkedHashMap<String, Integer>();
        TaskManager tm = Game.getWorld().getTaskManager();
        for (Action action : tm.getCustomActions()) {
            countPicks(action, hmNeed);
        }
        synchronized (tm.getCustomActionsTemp()) {
            for (Action action : tm.getCustomActionsTemp()) {
                countPicks(action, hmNeed);
            }
        }
        for (Action action : tm.getCustomActionsWait()) {
            countPicks(action, hmNeed);
        }
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen) {
                countPicks(((Citizen) le).getCurrentCustomAction(), hmNeed);
            }
        }

        LinkedHashMap<String, Integer> hmShort = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : hmNeed.entrySet()) {
            int iStock = Item.getNumItems(UtilsIniHeaders.getIntIniHeader(entry.getKey()), false, World.MAP_DEPTH - 1);
            if (entry.getValue().intValue() > iStock) {
                hmShort.put(entry.getKey(), Integer.valueOf(entry.getValue().intValue() - iStock));
            }
        }
        return hmShort;
    }

    /**
     * Counts each pick of a building material in a job's queue.  A pick
     * that lists several items counts for the first, and only when every
     * one of them is a building material.
     */
    private static void countPicks(Action action, HashMap<String, Integer> hmNeed) {
        if (action == null || action.getQueue() == null) {
            return;
        }
        for (QueueItem qi : action.getQueue()) {
            if (qi.getType() != QueueItem.TYPE_PICK || qi.getValue() == null) {
                continue;
            }
            ArrayList<String> alPicks = UtilsString.getArray(qi.getValue());
            if (alPicks.isEmpty()) {
                continue;
            }
            boolean bAllSupply = true;
            for (String sPick : alPicks) {
                if (!isSupplyItem(sPick)) {
                    bAllSupply = false;
                    break;
                }
            }
            if (bAllSupply) {
                Integer iCount = hmNeed.get(alPicks.get(0));
                hmNeed.put(alPicks.get(0), Integer.valueOf(iCount == null ? 1 : iCount.intValue() + 1));
            }
        }
    }

    private static boolean isSupplyItem(String sIniHeader) {
        ItemManagerItem imi = ItemManager.getItem(sIniHeader);
        return imi != null && SUPPLY_TYPE.equals(imi.getType());
    }

    /**
     * Delivers what the coins pay for, one material after another, and logs
     * what came and what it cost
     */
    private static void deliver(LinkedHashMap<String, Integer> hmShort) {
        Point3DShort p3dCenter = getDropCenter();
        if (p3dCenter == null) {
            return;
        }
        int iASZID = getCrewZone();
        // A day's bills stay in the bank: spent on concrete, they left the
        // site below zero at dawn (the rework play, 2026-10-30)
        int iCoins = Game.getWorld().getCoins() - DataCenter.getBillsPerDay();
        int iPaid = 0;
        boolean bShortOfCoins = false;
        boolean bShortOfRoom = false;
        StringBuilder sbWhat = new StringBuilder();
        int[] aiRadius = {0};
        for (Map.Entry<String, Integer> entry : hmShort.entrySet()) {
            ItemManagerItem imi = ItemManager.getItem(entry.getKey());
            int iPrice = Math.max(1, imi.getValue());
            int iWanted = entry.getValue().intValue();
            int iCount = 0;
            while (iCount < iWanted) {
                if (iCoins - iPaid < iPrice) {
                    bShortOfCoins = true;
                    break;
                }
                if (!dropOne(imi, p3dCenter, iASZID, aiRadius)) {
                    bShortOfRoom = true;
                    break;
                }
                iPaid += iPrice;
                iCount++;
            }
            if (iCount > 0) {
                if (sbWhat.length() > 0) {
                    sbWhat.append(Messages.getString("DataCenter.supplyAnd")); //$NON-NLS-1$
                }
                sbWhat.append(iCount).append(' ').append(imi.getName().toLowerCase());
            }
        }
        if (iPaid > 0) {
            Game.getWorld().setCoins(Game.getWorld().getCoins() - iPaid);
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.supplyDelivered"), sbWhat.toString(), iPaid), ColorGL.WHITE, new Point3DShort(p3dCenter.x, p3dCenter.y, p3dCenter.z)); //$NON-NLS-1$
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " delivered " + sbWhat + " for " + iPaid); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        if ((bShortOfCoins || bShortOfRoom) && iDay != noCoinsDay) {
            noCoinsDay = iDay;
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString(bShortOfCoins ? "DataCenter.supplyNoCoins" : "DataCenter.supplyNoRoom"), ColorGL.ORANGE, new Point3DShort(p3dCenter.x, p3dCenter.y, p3dCenter.z)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Puts one item on the nearest free cell around the center, searching
     * outward from the last ring that had room
     */
    private static boolean dropOne(ItemManagerItem imi, Point3DShort p3dCenter, int iASZID, int[] aiRadius) {
        int z = p3dCenter.z;
        for (int iRadius = aiRadius[0]; iRadius <= MAX_RADIUS; iRadius++) {
            for (int dy = -iRadius; dy <= iRadius; dy++) {
                for (int dx = -iRadius; dx <= iRadius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != iRadius) {
                        continue;
                    }
                    int x = p3dCenter.x + dx;
                    int y = p3dCenter.y + dy;
                    if (isDropCell(imi, x, y, z, iASZID)) {
                        Item item = Item.createItem(imi);
                        item.setOperative(true);
                        item.setLocked(false);
                        item.init(x, y, z);
                        World.getCell(x, y, z).setEntity(item);
                        aiRadius[0] = iRadius;
                        return true;
                    }
                }
            }
        }
        aiRadius[0] = MAX_RADIUS + 1;
        return false;
    }

    private static boolean isDropCell(ItemManagerItem imi, int x, int y, int z, int iASZID) {
        if (x < 1 || y < 1 || x >= World.MAP_WIDTH - 1 || y >= World.MAP_HEIGHT - 1 || z < 0 || z >= World.MAP_DEPTH) {
            return false;
        }
        Cell cell = World.getCell(x, y, z);
        if (cell.hasZone() || cell.isFlagOrders() || (iASZID != -1 && cell.getAstarZoneID() != iASZID)) {
            return false;
        }
        if (!Item.isCellAvailableForItem(imi, x, y, z, true, true, true, true) || Item.shouldFall(World.getCells(), imi, Point3DShort.getPoolInstance(x, y, z))) {
            return false;
        }
        for (int ny = y - 1; ny <= y + 1; ny++) {
            for (int nx = x - 1; nx <= x + 1; nx++) {
                Item itemNear = World.getCell(nx, ny, z).getItem();
                if (itemNear != null) {
                    ItemManagerItem imiNear = ItemManager.getItem(itemNear.getIniHeader());
                    if (imiNear != null && imiNear.getSpawn() != null) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * @return the middle of the first loading dock, else the grid feed, else
     *         the first technician
     */
    private static Point3DShort getDropCenter() {
        for (Zone zone : Game.getWorld().getZones()) {
            if (DOCK_ZONE.equals(zone.getIniHeader()) && !zone.getPoints().isEmpty()) {
                long lX = 0;
                long lY = 0;
                for (Point3DShort p3d : zone.getPoints()) {
                    lX += p3d.x;
                    lY += p3d.y;
                }
                int iSize = zone.getPoints().size();
                return Point3DShort.getPoolInstance((int) (lX / iSize), (int) (lY / iSize), zone.getPoints().get(0).z);
            }
        }
        for (Item item : World.getItems().values()) {
            ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
            if (imi != null && imi.getDcSource() == DataCenter.SOURCE_GRID && DataCenter.isInstalled(item)) {
                return Point3DShort.getPoolInstance(item.getX(), item.getY(), item.getZ());
            }
        }
        LivingEntity le = World.getLivingEntityByID(World.getCitizenIDs().get(0).intValue());
        return le == null ? null : Point3DShort.getPoolInstance(le.getX(), le.getY(), le.getZ());
    }

    /**
     * @return the walking area the first technician stands in (deliveries
     *         go where the crew can reach them), or -1 when unknown
     */
    private static int getCrewZone() {
        LivingEntity le = World.getLivingEntityByID(World.getCitizenIDs().get(0).intValue());
        if (le == null) {
            return -1;
        }
        return World.getCell(le.getX(), le.getY(), le.getZ()).getAstarZoneID();
    }
}
