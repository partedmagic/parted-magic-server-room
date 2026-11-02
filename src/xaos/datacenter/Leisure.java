package xaos.datacenter;

import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import xaos.main.Game;
import xaos.main.World;
import xaos.tiles.Cell;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.zones.Zone;
import xaos.zones.ZoneManager;
import xaos.zones.ZoneManagerItem;

/**
 * Something to do, and a proper break room (the second stakes pass,
 * 2026-10-31; Patrick: "by day 15 morale goes down if the people do not
 * have something more to do. add computer games or a TV to their rooms and
 * possibly a tavern for the builders? break area must have walls and a roof
 * by then too").
 *
 * Idle time costs morale outside a SOCIAL zone (LivingEntity).  From
 * FROM_DAY a SOCIAL zone, the break room or the donut shop, only counts as
 * rest for someone when it is walled and roofed (isEnclosed) and they have
 * something to do: a TV or a game console in their own bunk room, or, for a
 * construction worker, a walled and roofed donut shop with a donut case on
 * the site.
 *
 * The donut shop was the tavern until 2026-11-01 (Patrick: "in towns if you
 * didn't have a tavern, the heroes would show up. In this game if you don't
 * have a donut shop, the guards won't show up", then "remove the tavern in
 * this game and replace it with donut shop").  Its zone is still ztavern and
 * its counter dcbar, so saves keep them; guards come only to a site that has
 * one (hasDonutShop, Protests.updateGuards).
 * From WARN_FROM_DAY the orange row counts who has nothing to do and says
 * when the break room is open.  Never in a tutorial.
 *
 * Worked out at most once a game hour (a zone's flood fill is not free);
 * nothing is saved.
 */
public final class Leisure {

    static final int FROM_DAY = 15;
    private static final int WARN_FROM_DAY = 12;
    // A walled room bigger than this is taken for open ground
    private static final int MAX_ROOM = 400;
    private static final String TV = "dctv"; //$NON-NLS-1$
    private static final String CONSOLE = "dcconsole"; //$NON-NLS-1$
    // The donut case and the donut shop (the old tavern's IDs)
    private static final String BAR = "dcbar"; //$NON-NLS-1$
    private static final String TAVERN = "ztavern"; //$NON-NLS-1$
    private static final String BREAK_ROOM = "zdining"; //$NON-NLS-1$

    private static int checkedHour = Integer.MIN_VALUE;
    private static final HashMap<Integer, Boolean> hmEnclosed = new HashMap<Integer, Boolean>();
    // Crew with something to do, by living ID
    private static final HashSet<Integer> hsBusy = new HashSet<Integer>();
    private static boolean breakRoomOpen;

    private Leisure() {
    }

    static void reset() {
        checkedHour = Integer.MIN_VALUE;
        hmEnclosed.clear();
        hsBusy.clear();
        breakRoomOpen = false;
    }

    private static boolean isActive() {
        return Game.getWorld() != null && !DataCenter.isTutorial() && Game.getWorld().getDate().getDaysSinceStart() >= FROM_DAY;
    }

    /**
     * Whether idle time in this SOCIAL zone is rest for this person
     * (LivingEntity): always before FROM_DAY, then only in a walled and
     * roofed zone and for someone with something to do
     */
    public static boolean restsIn(Citizen cit, Zone zone) {
        if (!isActive()) {
            return true;
        }
        refresh();
        Boolean bEnclosed = hmEnclosed.get(Integer.valueOf(zone.getID()));
        return bEnclosed != null && bEnclosed.booleanValue() && hsBusy.contains(Integer.valueOf(cit.getID()));
    }

    /**
     * Recount, at most once a game hour: which SOCIAL zones are walled and
     * roofed, whether a donut shop with a donut case is, and who has
     * something to do
     */
    private static void refresh() {
        int iHour = Bear.now() / World.TIME_MODIFIER_HOUR;
        if (iHour == checkedHour) {
            return;
        }
        checkedHour = iHour;
        hmEnclosed.clear();
        hsBusy.clear();
        boolean bTavern = false;
        boolean bBreakRoom = false;
        boolean bBreakRoomShut = false;
        ArrayList<Zone> alZones = Game.getWorld().getZones();
        for (int i = 0; i < alZones.size(); i++) {
            Zone zone = alZones.get(i);
            ZoneManagerItem zmi = ZoneManager.getItem(zone.getIniHeader());
            if (zmi == null || zmi.getType() != ZoneManagerItem.TYPE_SOCIAL) {
                continue;
            }
            boolean bEnclosed = isEnclosed(zone);
            hmEnclosed.put(Integer.valueOf(zone.getID()), Boolean.valueOf(bEnclosed));
            if (BREAK_ROOM.equals(zone.getIniHeader())) {
                bBreakRoom = true;
                bBreakRoomShut |= bEnclosed;
            } else if (TAVERN.equals(zone.getIniHeader()) && bEnclosed && holds(zone, BAR)) {
                bTavern = true;
            }
        }
        breakRoomOpen = bBreakRoom && !bBreakRoomShut;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (!(le instanceof Citizen)) {
                continue;
            }
            Citizen cit = (Citizen) le;
            int iZone = cit.getCitizenData().getZoneID();
            Zone room = iZone > 0 ? Zone.getZone(iZone) : null;
            if ((room != null && (holds(room, TV) || holds(room, CONSOLE))) || (bTavern && Crew.isBuilder(cit))) {
                hsBusy.add(iID);
            }
        }
    }

    /**
     * @return whether the site has a donut shop with a donut case in it,
     *         walled or not: guards come only then (Protests.updateGuards)
     */
    static boolean hasDonutShop() {
        if (Game.getWorld() == null) {
            return false;
        }
        ArrayList<Zone> alZones = Game.getWorld().getZones();
        for (int i = 0; i < alZones.size(); i++) {
            Zone zone = alZones.get(i);
            if (TAVERN.equals(zone.getIniHeader()) && holds(zone, BAR)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holds(Zone zone, String sIniHeader) {
        for (Point3DShort p : zone.getPoints()) {
            Item item = World.getCell(p.x, p.y, p.z).getItem();
            if (item != null && sIniHeader.equals(item.getIniHeader()) && item.isLocked()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walled and roofed: walking from the zone on its level without going
     * through a wall, a window or a door, every cell reached has a roof (or
     * anything else that keeps the rain off, Weather.isUnderRoof) over it,
     * and the room is not bigger than MAX_ROOM.  Diagonal steps count, as
     * the pathfinder takes them between two walls' corners
     */
    static boolean isEnclosed(Zone zone) {
        if (zone.getPoints() == null || zone.getPoints().isEmpty()) {
            return false;
        }
        HashSet<Long> hsSeen = new HashSet<Long>();
        ArrayDeque<Point3DShort> queue = new ArrayDeque<Point3DShort>();
        for (Point3DShort p : zone.getPoints()) {
            if (hsSeen.add(key(p.x, p.y, p.z))) {
                queue.add(p);
            }
        }
        while (!queue.isEmpty()) {
            Point3DShort p = queue.poll();
            if (!Weather.isUnderRoof(p.x, p.y, p.z)) {
                return false;
            }
            if (hsSeen.size() > MAX_ROOM) {
                return false;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = p.x + dx, y = p.y + dy;
                    if ((dx == 0 && dy == 0) || x < 0 || y < 0 || x >= World.MAP_WIDTH || y >= World.MAP_HEIGHT) {
                        continue;
                    }
                    if (!hsSeen.add(key(x, y, p.z))) {
                        continue;
                    }
                    Cell cell = World.getCell(x, y, p.z);
                    if (!cell.isMined()) {
                        continue;
                    }
                    Item item = cell.getItem();
                    ItemManagerItem imi = item == null ? null : ItemManager.getItem(item.getIniHeader());
                    if (imi != null && (imi.isWall() || imi.isDoor() || imi.isWallConnector())) {
                        continue;
                    }
                    queue.add(Point3DShort.getPoolInstance(x, y, p.z));
                }
            }
        }
        return true;
    }

    private static long key(int x, int y, int z) {
        return ((long) z << 40) | ((long) x << 20) | y;
    }

    /**
     * @return the orange row's parts, or null: from WARN_FROM_DAY, who has
     *         nothing to do and an open break room, with the day they start
     *         to matter before FROM_DAY
     */
    static String getStatus(boolean bTutorial) {
        if (bTutorial || Game.getWorld().getDate().getDaysSinceStart() < WARN_FROM_DAY) {
            return null;
        }
        refresh();
        boolean bSoon = Game.getWorld().getDate().getDaysSinceStart() < FROM_DAY;
        ArrayList<String> alParts = new ArrayList<String>();
        int iBored = 0;
        for (Integer iID : World.getCitizenIDs()) {
            if (!hsBusy.contains(iID)) {
                iBored++;
            }
        }
        if (iBored > 0) {
            alParts.add(MessageFormat.format(Messages.getString(bSoon ? "DataCenter.statusBoredSoon" : "DataCenter.statusBored"), iBored, FROM_DAY)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (breakRoomOpen) {
            alParts.add(MessageFormat.format(Messages.getString(bSoon ? "DataCenter.statusBreakRoomSoon" : "DataCenter.statusBreakRoom"), FROM_DAY)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return alParts.isEmpty() ? null : String.join("   ", alParts); //$NON-NLS-1$
    }
}
