package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.PriorityQueue;

import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tiles.Cell;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;

/**
 * Copper thieves (the second stakes pass, 2026-10-31; Patrick: "maybe after
 * day 20 Copper Thieves steal entire pieces of equipment to scrap").
 *
 * From FROM_DAY, at each dawn one night in three is a theft night: in the
 * night (comeTurn, Bear.now()) two or three thieves walk in from the nearest
 * edge, each to its own piece of installed gear (anything with power,
 * cooling, ports, pay or a power source, but not the grid feed).  Next to
 * it, a thief works an hour to cut it loose and carries it off: it is gone,
 * and a rack with a customer loses the customer (DataCenter.rackStolen).
 *
 * Doors do not stop them (Protests.walksThroughDoors; Patrick, 2026-11-01:
 * "only guards stop people"), and walls only for a while (Patrick picked
 * thieves who get through walls: in the big play walls and doors kept out
 * every thief, bear and protest from day 21).  A thief that cannot get to
 * its gear looks for the cheapest way in (findWayIn) and breaks through:
 * two hours for a concrete wall or block, one for a window (CUT_HOURS).
 * The hole stays until something is built there again, and lets the bear
 * in too.  A guard on duty, awake and not eating (Protests.isOnDuty), goes
 * after a thief he or a running camera sees (Protests, through
 * findThief), and a thief that comes within GUARD_RANGE of one is chased
 * off.  A thief with no way in gives up after GIVE_UP_TURNS, and every
 * thief leaves when the sun comes up.  Never in a tutorial.
 *
 * Saved with the game (Game.SAVEGAME_THIEVES; the wall each thief breaks
 * and the holes since Game.SAVEGAME_DIESEL).
 */
public final class Thieves {

    static final int FROM_DAY = 20;
    private static final int NIGHT_PCT = 33;
    private static final int UNHOOK_TURNS = World.TIME_MODIFIER_HOUR;
    private static final int GIVE_UP_TURNS = 2 * World.TIME_MODIFIER_HOUR;
    private static final int REPATH_TURNS = 40;
    private static final int GUARD_RANGE = 3;
    private static final int CAMERA_RANGE = 8;
    private static final String[] TYPES = {"dcthief1", "dcthief2", "dcthief3"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final String GUARD = Crew.GUARD_TYPE;
    private static final String CAMERA = "dccamera"; //$NON-NLS-1$

    // What a thief breaks through, and the hours it takes: the build menu's
    // concrete wall and block, and window (a door they walk through)
    private static final HashMap<String, Integer> CUT_HOURS = new HashMap<String, Integer>();
    static {
        CUT_HOURS.put("stonewall", Integer.valueOf(2)); //$NON-NLS-1$
        CUT_HOURS.put("stoneblock", Integer.valueOf(2)); //$NON-NLS-1$
        CUT_HOURS.put("woodenglasswindow", Integer.valueOf(1)); //$NON-NLS-1$
    }
    // The way in: a step costs 1, and a wall to break through this much for
    // each hour it takes.  How far around the thief the way is looked for,
    // and how far around the gear for a cell to walk to first
    private static final int CUT_COST_PER_HOUR = 20;
    private static final int SEARCH_RADIUS = 24;
    private static final int APPROACH_RADIUS = 30;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DY = {0, 0, 1, -1};
    // Days a hole shows in the status line when nothing is built there again
    private static final int HOLE_DAYS = 3;

    private static final int GO = 1;
    private static final int CUT = 2;
    private static final int AWAY = 3;
    private static final int TO_WALL = 4;
    private static final int BREAK = 5;

    // The turn tonight's thieves come (Bear.now(), -1: none), the cell they
    // came in by, and each thief: living ID, phase, target cell key, timer,
    // spotted by a camera (0 or 1), the cell key of the wall, block or window
    // it breaks through (-1: none)
    private static int comeTurn = -1;
    private static long entryKey = -1;
    private static final ArrayList<long[]> alThieves = new ArrayList<long[]>();

    // The holes broken in walls, blocks and windows: cell key, the day
    private static final ArrayList<long[]> alHoles = new ArrayList<long[]>();

    private Thieves() {
    }

    static void reset() {
        comeTurn = -1;
        entryKey = -1;
        alThieves.clear();
        alHoles.clear();
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(comeTurn);
        out.writeLong(entryKey);
        out.writeInt(alThieves.size());
        for (long[] t : alThieves) {
            for (long l : t) {
                out.writeLong(l);
            }
        }
        out.writeInt(alHoles.size());
        for (long[] h : alHoles) {
            out.writeLong(h[0]);
            out.writeLong(h[1]);
        }
    }

    static void readExternal(ObjectInput in) throws IOException {
        comeTurn = in.readInt();
        entryKey = in.readLong();
        // Before Game.SAVEGAME_DIESEL a thief broke through nothing
        int iFields = Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_DIESEL ? 6 : 5;
        for (int n = in.readInt(); n > 0; n--) {
            long[] t = new long[] {0, 0, 0, 0, 0, -1};
            for (int i = 0; i < iFields; i++) {
                t[i] = in.readLong();
            }
            alThieves.add(t);
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_DIESEL) {
            for (int n = in.readInt(); n > 0; n--) {
                alHoles.add(new long[] {in.readLong(), in.readLong()});
            }
        }
    }

    /**
     * Dawn, from FROM_DAY: one night in three the thieves come
     */
    static void newDay(boolean bTutorial) {
        if (bTutorial || Game.getWorld().getDate().getDaysSinceStart() < FROM_DAY) {
            return;
        }
        if (Utils.getRandomBetween(1, 100) <= NIGHT_PCT) {
            comeTurn = Bear.now() + Utils.getRandomBetween(19, 23) * World.TIME_MODIFIER_HOUR;
        }
    }

    static void nextTurn(boolean bTutorial) {
        if (bTutorial) {
            return;
        }
        if (comeTurn >= 0 && Bear.now() >= comeTurn) {
            comeTurn = -1;
            come();
        }
        if (!alThieves.isEmpty()) {
            moveThieves();
        }
        if (!alHoles.isEmpty()) {
            checkHoles();
        }
    }

    /**
     * @return installed gear a thief can take: anything with power, cooling,
     *         ports, pay or a power source, but not the grid feed
     */
    private static ArrayList<Item> getLoot() {
        ArrayList<Item> al = new ArrayList<Item>();
        for (Item item : World.getItems().values()) {
            ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
            if (imi == null || !DataCenter.isInstalled(item) || imi.getDcSource() == 1) {
                continue;
            }
            if (imi.getDcPower() != 0 || imi.getDcCooling() > 0 || imi.getDcPorts() > 0 || imi.getDcIncome() > 0 || imi.getDcSource() > 0) {
                al.add(item);
            }
        }
        return al;
    }

    private static void come() {
        ArrayList<Item> alLoot = getLoot();
        if (alLoot.isEmpty()) {
            return;
        }
        Item first = alLoot.get(Utils.getRandomBetween(0, alLoot.size() - 1));
        Point3DShort border = findEntry(first);
        if (border == null) {
            return;
        }
        entryKey = key(border.x, border.y, border.z);
        int n = Utils.getRandomBetween(2, 3);
        ArrayList<Item> alFree = new ArrayList<Item>(alLoot);
        alFree.remove(first);
        for (int i = 0; i < n; i++) {
            Item target = i == 0 ? first : (alFree.isEmpty() ? first : alFree.remove(Utils.getRandomBetween(0, alFree.size() - 1)));
            LivingEntity le = World.addNewLiving(TYPES[i % TYPES.length], LivingEntity.TYPE_FRIENDLY, true, border.x, border.y, border.z, true);
            if (le == null) {
                continue;
            }
            alThieves.add(new long[] {le.getID(), GO, key(target.getX(), target.getY(), target.getZ()), 0, 0, -1});
            goTo(le, target);
        }
        if (alThieves.isEmpty()) {
            return;
        }
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + alThieves.size() + " copper thieves come at " + border.x + "," + border.y + "," + border.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.thievesCome"), alThieves.size()), ColorGL.RED, new Point3DShort(border.x, border.y, border.z)); //$NON-NLS-1$
    }

    /**
     * @return the edge cell the thieves come in by: the nearest to the gear
     *         of a dozen tries in the gear's own A* zone, or, when walls with
     *         no door shut that zone off from every edge, the nearest of a
     *         few dozen walkable edge cells
     */
    private static Point3DShort findEntry(Item first) {
        Point3DShort border = null;
        int iZone = World.getCell(first.getCoordinates()).getAstarZoneID();
        for (int i = 0; i < 12; i++) {
            border = nearer(World.getRandomBorderPoint(iZone), border, first);
        }
        if (border == null) {
            for (int i = 0; i < 36; i++) {
                border = nearer(randomEdgeCell(), border, first);
            }
        }
        return border;
    }

    private static Point3DShort nearer(Point3DShort p3d, Point3DShort best, Item first) {
        if (p3d == null || (best != null && Math.abs(p3d.x - first.getX()) + Math.abs(p3d.y - first.getY()) >= Math.abs(best.x - first.getX()) + Math.abs(best.y - first.getY()))) {
            return best;
        }
        return new Point3DShort(p3d.x, p3d.y, p3d.z);
    }

    /**
     * @return the first walkable cell going up from the lowest outside level
     *         at a random spot on the map's edge (as World.getRandomBorderPoint
     *         looks), or null
     */
    private static Point3DShort randomEdgeCell() {
        int iSide = Utils.getRandomBetween(1, 4);
        int x, y;
        if (iSide <= 2) {
            x = Utils.getRandomBetween(0, World.MAP_WIDTH - 1);
            y = iSide == 1 ? 0 : World.MAP_HEIGHT - 1;
        } else {
            x = iSide == 3 ? World.MAP_WIDTH - 1 : 0;
            y = Utils.getRandomBetween(0, World.MAP_HEIGHT - 1);
        }
        for (int z = World.MAP_NUM_LEVELS_OUTSIDE - 1; z >= 0; z--) {
            if (LivingEntity.isCellAllowed(x, y, z)) {
                return new Point3DShort((short) x, (short) y, (short) z);
            }
        }
        return null;
    }

    private static void moveThieves() {
        boolean bDaylight = DataCenter.isDaylight();
        for (int i = alThieves.size() - 1; i >= 0; i--) {
            long[] t = alThieves.get(i);
            LivingEntity le = World.getLivingEntityByID((int) t[0]);
            if (le == null) {
                alThieves.remove(i);
                continue;
            }
            if (t[1] != AWAY) {
                // They work in the dark: at sunrise whoever is still on the
                // site leaves, with nothing if it was still cutting
                if (bDaylight) {
                    away(le, t);
                    continue;
                }
                spot(le, t);
                if (nearGuard(le)) {
                    MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.thiefChased"), ColorGL.GREEN, le.getCoordinates()); //$NON-NLS-1$
                    away(le, t);
                    continue;
                }
            }
            boolean bIdle = le.getPath().isEmpty() && !le.isWaitingForPath();
            Item target = getItemAt(t[2]);
            if (t[1] == GO) {
                if (target == null) {
                    away(le, t);
                } else if (bIdle && isNextTo(le, target)) {
                    t[1] = CUT;
                    t[3] = UNHOOK_TURNS;
                } else if (bIdle) {
                    t[3]++;
                    if (t[3] >= GIVE_UP_TURNS) {
                        giveUp(le, t);
                    } else if (t[3] % REPATH_TURNS == 0 && !findWayIn(le, t, target)) {
                        goTo(le, target);
                    }
                }
            } else if (t[1] == TO_WALL || t[1] == BREAK) {
                Item wall = getWallAt(t[5]);
                if (target == null) {
                    away(le, t);
                } else if (wall == null) {
                    // Broken through, by this thief or another, or gone: on
                    // to the gear
                    onToTheGear(le, t, target);
                } else if (t[1] == BREAK) {
                    if (--t[3] <= 0) {
                        breakThrough(wall);
                        onToTheGear(le, t, target);
                    }
                } else if (bIdle && isNextTo(le, wall)) {
                    startBreaking(t, wall);
                } else if (bIdle) {
                    t[3]++;
                    if (t[3] >= GIVE_UP_TURNS) {
                        giveUp(le, t);
                    } else if (t[3] % REPATH_TURNS == 0) {
                        goBeside(le, wall);
                    }
                }
            } else if (t[1] == CUT) {
                if (target == null) {
                    away(le, t);
                } else if (--t[3] <= 0) {
                    steal(target);
                    away(le, t);
                }
            } else {
                // Gone at the edge, or into the dark two hours after leaving
                t[3]++;
                Point3DShort p3d = le.getCoordinates();
                boolean bEdge = p3d.x == 0 || p3d.y == 0 || p3d.x == World.MAP_WIDTH - 1 || p3d.y == World.MAP_HEIGHT - 1;
                if (bEdge || key(p3d.x, p3d.y, p3d.z) == entryKey || t[3] > 2 * World.TIME_MODIFIER_HOUR) {
                    le.delete();
                    alThieves.remove(i);
                }
            }
        }
    }

    private static void giveUp(LivingEntity le, long[] t) {
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.thiefBlocked"), ColorGL.GREEN, le.getCoordinates()); //$NON-NLS-1$
        away(le, t);
    }

    private static void onToTheGear(LivingEntity le, long[] t, Item target) {
        t[1] = GO;
        t[3] = 0;
        t[5] = -1;
        goTo(le, target);
    }

    /**
     * A thief that cannot get to its gear looks for the cheapest way in on
     * its own level, SEARCH_RADIUS around it: a step costs 1 (through a door
     * too), and a wall, block or window CUT_COST_PER_HOUR for each hour it
     * takes to break
     * through.  Found, the thief walks beside the first one on the way, or
     * starts on it when it is there already.  With no way that near, it
     * walks first to the cell nearest the gear that it can get to.
     *
     * @return false when there is nothing to do but head for the gear again
     */
    private static boolean findWayIn(LivingEntity le, long[] t, Item target) {
        int x0 = le.getX(), y0 = le.getY(), z = le.getZ();
        int iSide = 2 * SEARCH_RADIUS + 1;
        int[] aiCost = new int[iSide * iSide];
        int[] aiFrom = new int[iSide * iSide];
        Arrays.fill(aiCost, Integer.MAX_VALUE);
        int iStart = SEARCH_RADIUS * iSide + SEARCH_RADIUS;
        aiCost[iStart] = 0;
        aiFrom[iStart] = -1;
        PriorityQueue<int[]> pq = new PriorityQueue<int[]>((a, b) -> Integer.compare(a[0], b[0]));
        pq.add(new int[] {0, iStart});
        int iGoal = -1;
        while (!pq.isEmpty()) {
            int[] e = pq.poll();
            int i = e[1];
            if (e[0] > aiCost[i]) {
                continue;
            }
            int x = x0 + i % iSide - SEARCH_RADIUS, y = y0 + i / iSide - SEARCH_RADIUS;
            if (z == target.getZ() && Math.max(Math.abs(x - target.getX()), Math.abs(y - target.getY())) == 1 && stepCost(x, y, z) == 1) {
                iGoal = i;
                break;
            }
            for (int d = 0; d < DX.length; d++) {
                int nx = x + DX[d], ny = y + DY[d];
                if (Math.abs(nx - x0) > SEARCH_RADIUS || Math.abs(ny - y0) > SEARCH_RADIUS || nx < 0 || ny < 0 || nx >= World.MAP_WIDTH || ny >= World.MAP_HEIGHT) {
                    continue;
                }
                int c = stepCost(nx, ny, z);
                if (c < 0) {
                    continue;
                }
                int ni = (ny - y0 + SEARCH_RADIUS) * iSide + (nx - x0 + SEARCH_RADIUS);
                if (e[0] + c < aiCost[ni]) {
                    aiCost[ni] = e[0] + c;
                    aiFrom[ni] = i;
                    pq.add(new int[] {aiCost[ni], ni});
                }
            }
        }
        if (iGoal == -1) {
            Point3DShort p3d = reachableNear(World.getCell(le.getCoordinates()).getAstarZoneID(), target.getX(), target.getY(), target.getZ(), APPROACH_RADIUS);
            if (p3d != null && (p3d.z != z || Math.abs(p3d.x - x0) > 1 || Math.abs(p3d.y - y0) > 1)) {
                le.setDestination(p3d);
                return true;
            }
            return false;
        }
        // The first wall, block or window on the way from the thief, and the
        // cell before it
        ArrayList<Integer> alWay = new ArrayList<Integer>();
        for (int i = iGoal; i != -1; i = aiFrom[i]) {
            alWay.add(0, Integer.valueOf(i));
        }
        for (int k = 1; k < alWay.size(); k++) {
            int i = alWay.get(k).intValue();
            int x = x0 + i % iSide - SEARCH_RADIUS, y = y0 + i / iSide - SEARCH_RADIUS;
            if (stepCost(x, y, z) > 1) {
                t[5] = key(x, y, z);
                t[3] = 0;
                if (k == 1) {
                    startBreaking(t, World.getCell(x, y, z).getItem());
                } else {
                    int j = alWay.get(k - 1).intValue();
                    t[1] = TO_WALL;
                    le.setDestination(new Point3DShort((short) (x0 + j % iSide - SEARCH_RADIUS), (short) (y0 + j / iSide - SEARCH_RADIUS), (short) z));
                }
                return true;
            }
        }
        return false;
    }

    /**
     * @return what a step onto the cell costs a thief looking for a way in:
     *         1 for a cell anyone can walk, the hours to break through a
     *         wall, block or window times CUT_COST_PER_HOUR, or -1 for no way
     */
    private static int stepCost(int x, int y, int z) {
        Cell cell = World.getCell(x, y, z);
        Item item = cell.getItem();
        if (item != null && item.isLocked()) {
            Integer iHours = CUT_HOURS.get(item.getIniHeader());
            if (iHours != null) {
                return iHours.intValue() * CUT_COST_PER_HOUR;
            }
        }
        return LivingEntity.isCellAllowed(cell) ? 1 : -1;
    }

    private static void startBreaking(long[] t, Item wall) {
        boolean bFirst = true;
        for (long[] o : alThieves) {
            if (o != t && o[1] == BREAK && o[5] == t[5]) {
                bFirst = false;
            }
        }
        t[1] = BREAK;
        t[3] = CUT_HOURS.get(wall.getIniHeader()).intValue() * World.TIME_MODIFIER_HOUR;
        if (!bFirst) {
            return;
        }
        Point3DShort p3d = new Point3DShort(wall.getX(), wall.getY(), wall.getZ());
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " thieves breaking through " + wall.getIniHeader() + " at " + p3d.x + "," + p3d.y + "," + p3d.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.thiefBreaking"), withArticle(wall)), ColorGL.ORANGE, p3d); //$NON-NLS-1$
    }

    private static void breakThrough(Item wall) {
        String sName = withArticle(wall);
        String sIniHeader = wall.getIniHeader();
        Point3DShort p3d = new Point3DShort(wall.getX(), wall.getY(), wall.getZ());
        wall.delete();
        alHoles.add(new long[] {key(p3d.x, p3d.y, p3d.z), Game.getWorld().getDate().getDaysSinceStart()});
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " thieves broke through " + sIniHeader + " at " + p3d.x + "," + p3d.y + "," + p3d.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.thiefBrokeThrough"), sName), ColorGL.RED, p3d); //$NON-NLS-1$
    }

    /**
     * Each turn: a hole is forgotten once a wall, window or door stands there
     * again (a door keeps the bear out), or HOLE_DAYS after it was made
     */
    private static void checkHoles() {
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        for (int i = alHoles.size() - 1; i >= 0; i--) {
            long[] h = alHoles.get(i);
            if (getWallAt(h[0]) != null || isDoorAt(h[0]) || iDay - h[1] >= HOLE_DAYS) {
                alHoles.remove(i);
            }
        }
    }

    private static void steal(Item item) {
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        String sName = withArticle(item);
        Point3DShort p3d = new Point3DShort(item.getX(), item.getY(), item.getZ());
        if (imi != null && imi.getDcIncome() > 0) {
            DataCenter.rackStolen(item);
        }
        item.delete();
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " thieves took " + sName + " at " + p3d.x + "," + p3d.y + "," + p3d.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.thiefStole"), sName), ColorGL.RED, p3d); //$NON-NLS-1$
    }

    /**
     * @return the item's name in lower case after "a" or "an", for the log
     */
    private static String withArticle(Item item) {
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        String sName = (imi == null ? item.getIniHeader() : imi.getName()).toLowerCase();
        return ("aeiou".indexOf(sName.charAt(0)) >= 0 ? "an " : "a ") + sName; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void away(LivingEntity le, long[] t) {
        t[1] = AWAY;
        t[3] = 0;
        int x = (int) ((entryKey >> 20) & 0xFFFFF), y = (int) (entryKey & 0xFFFFF), z = (int) (entryKey >> 40);
        le.setDestination(new Point3DShort((short) x, (short) y, (short) z));
    }

    /**
     * A camera with the thief in range says so, once a thief
     */
    private static void spot(LivingEntity le, long[] t) {
        if (t[4] != 0) {
            return;
        }
        for (Item item : World.getItems().values()) {
            if (CAMERA.equals(item.getIniHeader()) && item.isOperative() && Math.abs(item.getZ() - le.getZ()) <= 1 && Math.abs(item.getX() - le.getX()) <= CAMERA_RANGE && Math.abs(item.getY() - le.getY()) <= CAMERA_RANGE) {
                t[4] = 1;
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.thiefSpotted"), ColorGL.ORANGE, le.getCoordinates(), le.getID()); //$NON-NLS-1$
                return;
            }
        }
    }

    /**
     * @return true when a guard on duty is within GUARD_RANGE: one asleep in
     *         his bunk or eating chases no one
     */
    private static boolean nearGuard(LivingEntity le) {
        for (LivingEntity guard : World.getLivings(true).values()) {
            if (GUARD.equals(guard.getIniHeader()) && Protests.isOnDuty(guard) && Math.abs(guard.getZ() - le.getZ()) <= 1 && Math.abs(guard.getX() - le.getX()) <= GUARD_RANGE && Math.abs(guard.getY() - le.getY()) <= GUARD_RANGE) {
                return true;
            }
        }
        return false;
    }

    /**
     * For a guard on duty (Protests): where to go for the nearest thief on
     * the site that he sees (iSight) or that a running camera sees
     * (iCameraRange).  A thief he cannot walk to, outside a wall, he meets at
     * the nearest cell he can reach, which is near enough to chase it off.
     *
     * @return the cell to walk to, or null with no thief seen
     */
    static Point3DShort findThief(LivingEntity guard, ArrayList<Item> alCameras, int iSight, int iCameraRange) {
        LivingEntity best = null;
        int iBest = Integer.MAX_VALUE;
        for (long[] t : alThieves) {
            if (t[1] == AWAY) {
                continue;
            }
            LivingEntity le = World.getLivingEntityByID((int) t[0]);
            if (le == null) {
                continue;
            }
            int d = distance(guard.getCoordinates(), le.getCoordinates());
            boolean bSeen = d <= iSight;
            for (int c = 0; c < alCameras.size() && !bSeen; c++) {
                bSeen = distance(alCameras.get(c).getCoordinates(), le.getCoordinates()) <= iCameraRange;
            }
            if (bSeen && d < iBest) {
                iBest = d;
                best = le;
            }
        }
        if (best == null) {
            return null;
        }
        int iZone = World.getCell(guard.getCoordinates()).getAstarZoneID();
        if (World.getCell(best.getCoordinates()).getAstarZoneID() == iZone) {
            return new Point3DShort(best.getX(), best.getY(), best.getZ());
        }
        return reachableNear(iZone, best.getX(), best.getY(), best.getZ(), GUARD_RANGE);
    }

    /**
     * @return the walkable cell in that A* zone nearest x, y on level z, ring
     *         by ring out to iRadius, that is no wall, block or window; or null
     */
    private static Point3DShort reachableNear(int iZone, int x0, int y0, int z, int iRadius) {
        for (int r = 1; r <= iRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    int x = x0 + dx, y = y0 + dy;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r || x < 0 || y < 0 || x >= World.MAP_WIDTH || y >= World.MAP_HEIGHT) {
                        continue;
                    }
                    if (stepCost(x, y, z) == 1 && World.getCell(x, y, z).getAstarZoneID() == iZone) {
                        return new Point3DShort((short) x, (short) y, (short) z);
                    }
                }
            }
        }
        return null;
    }

    private static int distance(Point3DShort a, Point3DShort b) {
        if (Math.abs(a.z - b.z) > 1) {
            return Integer.MAX_VALUE / 2;
        }
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
    }

    private static boolean isNextTo(LivingEntity le, Item item) {
        return le.getZ() == item.getZ() && Math.abs(le.getX() - item.getX()) <= 1 && Math.abs(le.getY() - item.getY()) <= 1;
    }

    /**
     * Toward a free cell next to the gear, on its level
     */
    private static void goTo(LivingEntity le, Item target) {
        Point3DShort best = null;
        int iBest = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = target.getX() + dx, y = target.getY() + dy;
                if ((dx == 0 && dy == 0) || x < 0 || y < 0 || x >= World.MAP_WIDTH || y >= World.MAP_HEIGHT || !LivingEntity.isCellAllowed(x, y, target.getZ())) {
                    continue;
                }
                int d = Math.abs(x - le.getX()) + Math.abs(y - le.getY());
                if (d < iBest) {
                    iBest = d;
                    best = new Point3DShort((short) x, (short) y, target.getZ());
                }
            }
        }
        if (best != null) {
            le.setDestination(best);
        }
    }

    /**
     * Toward a free cell beside the wall, block or window, on the thief's side
     * of it (its A* zone)
     */
    private static void goBeside(LivingEntity le, Item wall) {
        int iZone = World.getCell(le.getCoordinates()).getAstarZoneID();
        Point3DShort best = null;
        int iBest = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = wall.getX() + dx, y = wall.getY() + dy;
                if ((dx == 0 && dy == 0) || x < 0 || y < 0 || x >= World.MAP_WIDTH || y >= World.MAP_HEIGHT || stepCost(x, y, wall.getZ()) != 1 || World.getCell(x, y, wall.getZ()).getAstarZoneID() != iZone) {
                    continue;
                }
                int d = Math.abs(x - le.getX()) + Math.abs(y - le.getY());
                if (d < iBest) {
                    iBest = d;
                    best = new Point3DShort((short) x, (short) y, wall.getZ());
                }
            }
        }
        if (best != null) {
            le.setDestination(best);
        }
    }

    private static Item getItemAt(long lKey) {
        int x = (int) ((lKey >> 20) & 0xFFFFF), y = (int) (lKey & 0xFFFFF), z = (int) (lKey >> 40);
        Item item = World.getCell(x, y, z).getItem();
        return item != null && DataCenter.isInstalled(item) ? item : null;
    }

    /**
     * @return the wall, block or window a thief can break through in that
     *         cell, or null
     */
    private static Item getWallAt(long lKey) {
        if (lKey < 0) {
            return null;
        }
        int x = (int) ((lKey >> 20) & 0xFFFFF), y = (int) (lKey & 0xFFFFF), z = (int) (lKey >> 40);
        Item item = World.getCell(x, y, z).getItem();
        return item != null && item.isLocked() && CUT_HOURS.containsKey(item.getIniHeader()) ? item : null;
    }

    private static boolean isDoorAt(long lKey) {
        int x = (int) ((lKey >> 20) & 0xFFFFF), y = (int) (lKey & 0xFFFFF), z = (int) (lKey >> 40);
        Item item = World.getCell(x, y, z).getItem();
        if (item == null || !item.isLocked()) {
            return false;
        }
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        return imi != null && imi.isDoor();
    }

    /**
     * @return the orange row's part, or null with no thieves on the site
     */
    static String getStatus() {
        int n = 0;
        for (long[] t : alThieves) {
            if (t[1] != AWAY) {
                n++;
            }
        }
        return n == 0 ? null : MessageFormat.format(Messages.getString("DataCenter.statusThieves"), n); //$NON-NLS-1$
    }

    /**
     * @return the orange row's part for the holes thieves broke in walls,
     *         blocks and windows that nobody has built again, or null
     */
    static String getHolesStatus() {
        return alHoles.isEmpty() ? null : MessageFormat.format(Messages.getString("DataCenter.statusHoles"), alHoles.size()); //$NON-NLS-1$
    }

    private static long key(int x, int y, int z) {
        return ((long) z << 40) | ((long) x << 20) | y;
    }
}
