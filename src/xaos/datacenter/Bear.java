package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;

/**
 * The bear and the ghost (the second stakes pass, 2026-10-31; Patrick:
 * "maybe every 6-8 days a big grizzly bear kills a person and you have to
 * make a grave for them or they turn into a ghost?"; his calls the same day:
 * the bear is only avoided, never fought, and the ghost drains morale at
 * night).
 *
 * A grizzly comes every 6 to 8 days, the first between day 7 and day 9
 * (nextDay is rolled at the first dawn from ROLL_FROM_DAY, so no dice are
 * drawn before it), at an hour of that day.  It walks in from the nearest
 * edge toward the nearest crew member out in the open (no roof over them);
 * walls and doors stop it as they stop protesters (Protests
 * .isStoppedByDoors), and nobody fights it.  The first one it gets next to
 * dies, and it goes back to the woods; with nobody to catch for VISIT_HOURS
 * it goes anyway.
 *
 * The body ("dcbody") lies where they fell until construction workers bury
 * it (Bury, the bottom bar: qdcbury puts a grave in its place).  A body
 * left a day comes back as a ghost every night until then: each hour of the
 * night, everyone within GHOST_RANGE of it loses GHOST_HAPPINESS.  Never in
 * a tutorial.
 *
 * Saved with the game (Game.SAVEGAME_BEAR).
 */
public final class Bear {

    static final int ROLL_FROM_DAY = 6;
    private static final int VISIT_HOURS = 8;
    private static final int REPATH_TURNS = 40;
    private static final int GHOST_AFTER = World.TIME_MODIFIER_DAY;
    private static final int GHOST_HAPPINESS = 4;
    private static final int GHOST_RANGE = 7;
    private static final String BEAR_TYPE = "dcbear"; //$NON-NLS-1$
    private static final String GHOST_TYPE = "dcghost"; //$NON-NLS-1$
    static final String BODY = "dcbody"; //$NON-NLS-1$

    private static final int HUNT = 1;
    private static final int LEAVE = 2;

    // The day the next bear comes (-1 until rolled), the turn it comes (now(),
    // -1: not today), and the one here now
    private static int nextDay = -1;
    private static int comeTurn = -1;
    private static int bearID = -1;
    private static int phase;
    private static int bearTurns;
    private static int targetID = -1;
    private static long entryKey = -1;

    // Unburied bodies by cell key: the turn they fell (now()) and the name,
    // and the ghost walking for each tonight (living ID, or -1)
    private static final LinkedHashMap<Long, Object[]> hmBodies = new LinkedHashMap<Long, Object[]>();

    private Bear() {
    }

    static void reset() {
        nextDay = -1;
        comeTurn = -1;
        bearID = -1;
        phase = 0;
        bearTurns = 0;
        targetID = -1;
        entryKey = -1;
        hmBodies.clear();
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(nextDay);
        out.writeInt(comeTurn);
        out.writeInt(bearID);
        out.writeInt(phase);
        out.writeInt(bearTurns);
        out.writeInt(targetID);
        out.writeLong(entryKey);
        out.writeInt(hmBodies.size());
        for (Map.Entry<Long, Object[]> e : hmBodies.entrySet()) {
            out.writeLong(e.getKey().longValue());
            out.writeInt(((Integer) e.getValue()[0]).intValue());
            out.writeUTF((String) e.getValue()[1]);
            out.writeInt(((Integer) e.getValue()[2]).intValue());
        }
    }

    static void readExternal(ObjectInput in) throws IOException {
        nextDay = in.readInt();
        comeTurn = in.readInt();
        bearID = in.readInt();
        phase = in.readInt();
        bearTurns = in.readInt();
        targetID = in.readInt();
        entryKey = in.readLong();
        for (int n = in.readInt(); n > 0; n--) {
            long lKey = in.readLong();
            int iFell = in.readInt();
            String sName = in.readUTF();
            int iGhost = in.readInt();
            hmBodies.put(Long.valueOf(lKey), new Object[] {Integer.valueOf(iFell), sName, Integer.valueOf(iGhost)});
        }
    }

    /**
     * After a load: a living's name is not saved, so the ghosts get theirs
     * back
     */
    static void refresh() {
        for (Object[] body : hmBodies.values()) {
            LivingEntity ghost = World.getLivingEntityByID(((Integer) body[2]).intValue());
            if (ghost != null) {
                ghost.getLivingEntityData().setName(MessageFormat.format(Messages.getString("DataCenter.ghostName"), body[1])); //$NON-NLS-1$
            }
        }
    }

    /**
     * Dawn: roll when the next bear comes, and the hour if it is today
     */
    static void newDay(boolean bTutorial) {
        if (bTutorial) {
            return;
        }
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        if (nextDay < 0) {
            if (iDay >= ROLL_FROM_DAY) {
                nextDay = iDay + Utils.getRandomBetween(1, 3);
            }
            return;
        }
        if (iDay >= nextDay) {
            comeTurn = now() + Utils.getRandomBetween(0, 23) * World.TIME_MODIFIER_HOUR;
            nextDay = iDay + Utils.getRandomBetween(6, 8);
        }
    }

    /**
     * Every turn: the bear comes, hunts and goes; every hour the bodies are
     * checked and the ghosts walk by night
     */
    static void nextTurn(boolean bTutorial) {
        if (bTutorial) {
            return;
        }
        int iTurn = now();
        if (comeTurn >= 0 && iTurn >= comeTurn && bearID < 0) {
            comeTurn = -1;
            come();
        }
        if (bearID >= 0) {
            moveBear();
        }
        if (Game.getWorld().getTurn() % World.TIME_MODIFIER_HOUR == 0 && !hmBodies.isEmpty()) {
            checkBodies();
        }
    }

    private static void come() {
        // In from the nearest of a dozen edge cells, in the crew's part of
        // the map
        Citizen anyone = null;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen) {
                anyone = (Citizen) le;
                break;
            }
        }
        if (anyone == null) {
            return;
        }
        Point3DShort p3dCrew = anyone.getCoordinates();
        Point3DShort border = null;
        for (int i = 0; i < 12; i++) {
            Point3DShort p3d = World.getRandomBorderPoint(World.getCell(p3dCrew).getAstarZoneID());
            if (p3d != null && (border == null || Math.abs(p3d.x - p3dCrew.x) + Math.abs(p3d.y - p3dCrew.y) < Math.abs(border.x - p3dCrew.x) + Math.abs(border.y - p3dCrew.y))) {
                border = new Point3DShort(p3d.x, p3d.y, p3d.z);
            }
        }
        if (border == null) {
            return;
        }
        LivingEntity bear = World.addNewLiving(BEAR_TYPE, LivingEntity.TYPE_FRIENDLY, true, border.x, border.y, border.z, true);
        if (bear == null) {
            return;
        }
        bearID = bear.getID();
        phase = HUNT;
        bearTurns = 0;
        targetID = -1;
        entryKey = key(border.x, border.y, border.z);
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " a bear comes at " + border.x + "," + border.y + "," + border.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.bearComes"), ColorGL.ORANGE, new Point3DShort(border.x, border.y, border.z), bearID); //$NON-NLS-1$
    }

    private static void moveBear() {
        LivingEntity bear = World.getLivingEntityByID(bearID);
        if (bear == null) {
            bearID = -1;
            return;
        }
        bearTurns++;
        boolean bIdle = bear.getPath().isEmpty() && !bear.isWaitingForPath();
        if (phase == HUNT) {
            // Whoever is next to it on its level dies
            for (Integer iID : World.getCitizenIDs()) {
                LivingEntity le = World.getLivingEntityByID(iID.intValue());
                if (le instanceof Citizen && le.getZ() == bear.getZ() && Math.abs(le.getX() - bear.getX()) <= 1 && Math.abs(le.getY() - bear.getY()) <= 1) {
                    kill((Citizen) le);
                    leave(bear);
                    return;
                }
            }
            if (bearTurns >= VISIT_HOURS * World.TIME_MODIFIER_HOUR) {
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.bearLeaves"), ColorGL.GREEN, bear.getCoordinates()); //$NON-NLS-1$
                leave(bear);
                return;
            }
            if (bIdle || bearTurns % REPATH_TURNS == 0) {
                hunt(bear);
            }
        } else if (phase == LEAVE) {
            // Gone at the edge, or into the woods two hours after it turned
            Point3DShort p3d = bear.getCoordinates();
            boolean bEdge = p3d.x == 0 || p3d.y == 0 || p3d.x == World.MAP_WIDTH - 1 || p3d.y == World.MAP_HEIGHT - 1;
            if (bEdge || key(p3d.x, p3d.y, p3d.z) == entryKey || bearTurns > 2 * World.TIME_MODIFIER_HOUR) {
                bear.delete();
                bearID = -1;
                phase = 0;
            }
        }
    }

    /**
     * Toward the nearest crew member out in the open (no roof over them):
     * anyone under a roof is taken to be inside, where walls and doors keep
     * it out
     */
    private static void hunt(LivingEntity bear) {
        Citizen best = null;
        int iBest = Integer.MAX_VALUE;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (!(le instanceof Citizen) || Math.abs(le.getZ() - bear.getZ()) > 3 || Weather.isUnderRoof(le.getX(), le.getY(), le.getZ())) {
                continue;
            }
            int d = Math.abs(le.getX() - bear.getX()) + Math.abs(le.getY() - bear.getY());
            if (d < iBest) {
                iBest = d;
                best = (Citizen) le;
            }
        }
        if (best != null) {
            targetID = best.getID();
            bear.setDestination(best.getCoordinates());
        }
    }

    private static void leave(LivingEntity bear) {
        phase = LEAVE;
        bearTurns = 0;
        int x = (int) ((entryKey >> 20) & 0xFFFFF);
        int y = (int) (entryKey & 0xFFFFF);
        int z = (int) (entryKey >> 40);
        bear.setDestination(new Point3DShort((short) x, (short) y, (short) z));
    }

    private static void kill(Citizen cit) {
        String sName = cit.getCitizenData().getFullName();
        Point3DShort p3d = new Point3DShort(cit.getX(), cit.getY(), cit.getZ());
        // Gone the way a quitter goes (what they carry is dropped), so Towns'
        // corpse and its stink clouds (happiness -15 and -20 to anyone who
        // sees them) are not left on top of this body
        cit.deleteLeaving();
        Point3DShort p3dBody = placeBody(p3d);
        if (p3dBody != null) {
            hmBodies.put(Long.valueOf(key(p3dBody.x, p3dBody.y, p3dBody.z)), new Object[] {Integer.valueOf(now()), sName, Integer.valueOf(-1)});
        }
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " the bear killed " + sName + " at " + p3d.x + "," + p3d.y + "," + p3d.z); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.bearKill"), sName), ColorGL.RED, p3dBody != null ? p3dBody : p3d); //$NON-NLS-1$
    }

    /**
     * The body on the cell they fell on, or the nearest free one around it
     */
    private static Point3DShort placeBody(Point3DShort p3d) {
        ItemManagerItem imi = ItemManager.getItem(BODY);
        for (int r = 0; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    int x = p3d.x + dx, y = p3d.y + dy;
                    if (x < 0 || y < 0 || x >= World.MAP_WIDTH || y >= World.MAP_HEIGHT) {
                        continue;
                    }
                    Point3DShort p = Point3DShort.getPoolInstance(x, y, p3d.z);
                    if (Item.shouldFall(World.getCells(), imi, p) || !Item.isCellAvailableForItem(imi, x, y, p3d.z, false, false)) {
                        continue;
                    }
                    Item item = Item.createItem(imi);
                    item.setOperative(true);
                    item.setLocked(true);
                    item.init(x, y, p3d.z);
                    World.getCell(x, y, p3d.z).setEntity(item);
                    return new Point3DShort((short) x, (short) y, p3d.z);
                }
            }
        }
        return null;
    }

    /**
     * Every hour: a body buried (its item is gone) is laid to rest; one left
     * past GHOST_AFTER walks as a ghost by night, and everyone near the ghost
     * loses some morale
     */
    private static void checkBodies() {
        boolean bNight = !DataCenter.isDaylight();
        int iTurn = now();
        ArrayList<Long> alGone = new ArrayList<Long>();
        for (Map.Entry<Long, Object[]> e : hmBodies.entrySet()) {
            long lKey = e.getKey().longValue();
            Object[] body = e.getValue();
            int x = (int) ((lKey >> 20) & 0xFFFFF), y = (int) (lKey & 0xFFFFF), z = (int) (lKey >> 40);
            Item item = World.getCell(x, y, z).getItem();
            LivingEntity ghost = World.getLivingEntityByID(((Integer) body[2]).intValue());
            if (item == null || !BODY.equals(item.getIniHeader())) {
                alGone.add(e.getKey());
                if (ghost != null) {
                    ghost.delete();
                }
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.laidToRest"), body[1]), ColorGL.GREEN, new Point3DShort((short) x, (short) y, (short) z)); //$NON-NLS-1$
                continue;
            }
            boolean bHaunts = iTurn - ((Integer) body[0]).intValue() >= GHOST_AFTER;
            if (bNight && bHaunts) {
                if (ghost == null) {
                    ghost = World.addNewLiving(GHOST_TYPE, LivingEntity.TYPE_FRIENDLY, true, x, y, z, true);
                    if (ghost != null) {
                        body[2] = Integer.valueOf(ghost.getID());
                        ghost.getLivingEntityData().setName(MessageFormat.format(Messages.getString("DataCenter.ghostName"), body[1])); //$NON-NLS-1$
                        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.ghostWalks"), body[1]), ColorGL.ORANGE, new Point3DShort((short) x, (short) y, (short) z), ghost.getID()); //$NON-NLS-1$
                    }
                }
                if (ghost != null) {
                    haunt(ghost);
                }
            } else if (ghost != null) {
                ghost.delete();
                body[2] = Integer.valueOf(-1);
            }
        }
        for (Long lKey : alGone) {
            hmBodies.remove(lKey);
        }
    }

    private static void haunt(LivingEntity ghost) {
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen && Math.abs(le.getZ() - ghost.getZ()) <= 1 && Math.abs(le.getX() - ghost.getX()) <= GHOST_RANGE && Math.abs(le.getY() - ghost.getY()) <= GHOST_RANGE) {
                Citizen cit = (Citizen) le;
                cit.getCitizenData().setHappiness(cit.getCitizenData().getHappiness() - GHOST_HAPPINESS);
            }
        }
    }

    /**
     * @return the orange row's part, or null: the bear on the site, and the
     *         bodies not buried yet
     */
    static String getStatus() {
        ArrayList<String> alParts = new ArrayList<String>();
        if (bearID >= 0 && phase == HUNT) {
            alParts.add(Messages.getString("DataCenter.statusBear")); //$NON-NLS-1$
        }
        if (!hmBodies.isEmpty()) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusUnburied"), hmBodies.size())); //$NON-NLS-1$
        }
        return alParts.isEmpty() ? null : String.join("   ", alParts); //$NON-NLS-1$
    }

    /**
     * @return turns since the site started: World.getTurn() is the turn of
     *         the day, back to 0 at each dawn
     */
    static int now() {
        return Game.getWorld().getDate().getDaysSinceStart() * World.TIME_MODIFIER_DAY + Game.getWorld().getTurn();
    }

    /**
     * @return true for the ghost's living type, which MainPanel draws
     *         see-through and unlit, so it glows at night
     */
    public static boolean isGhost(String sIniHeader) {
        return GHOST_TYPE.equals(sIniHeader);
    }

    private static long key(int x, int y, int z) {
        return ((long) z << 40) | ((long) x << 20) | y;
    }
}
