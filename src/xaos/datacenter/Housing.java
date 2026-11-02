package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.HashSet;

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
import xaos.zones.Zone;

/**
 * A decent place to live (Patrick, 2026-10-31: "I also think the workers
 * need a roof on their house. maybe after day 10 they quit if they don't
 * have a decent place to live.").
 *
 * A crew member lives decently with a bunk room of their own (a personal
 * zone, Zones) holding a bed, a cot or a bunk bed, with a roof over it
 * (Weather.isUnderRoof).  From HOUSING_FROM_DAY, at dawn, one without is
 * warned in the log; one warned at the dawn before and still without leaves.
 * One person leaves a dawn at most, whether for this or for morale (Morale
 * goes first).  From WARN_FROM_DAY the orange row counts them.  Never in a
 * tutorial.
 *
 * Those warned are saved with the game (Game.SAVEGAME_CREW).
 */
public final class Housing {

    static final int HOUSING_FROM_DAY = 10;
    private static final int WARN_FROM_DAY = 6;

    private static final HashSet<Integer> hsWarned = new HashSet<Integer>();

    private Housing() {
    }

    static void reset() {
        hsWarned.clear();
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(hsWarned.size());
        for (Integer iID : hsWarned) {
            out.writeInt(iID.intValue());
        }
    }

    static void readExternal(ObjectInput in) throws IOException {
        for (int n = in.readInt(); n > 0; n--) {
            hsWarned.add(Integer.valueOf(in.readInt()));
        }
    }

    /**
     * Dawn: warn those without a roofed bed, and the first of those warned
     * before who still have none leaves, unless someone already quit this
     * dawn
     *
     * @param bSomeoneLeft true when Morale sent someone away this dawn
     */
    static void newDay(boolean bTutorial, boolean bSomeoneLeft) {
        if (bTutorial || Game.getWorld().getDate().getDaysSinceStart() < HOUSING_FROM_DAY) {
            hsWarned.clear();
            return;
        }
        Citizen leaver = null;
        HashSet<Integer> hsNow = new HashSet<Integer>();
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (!(le instanceof Citizen) || hasRoofedBed((Citizen) le)) {
                continue;
            }
            hsNow.add(iID);
            if (!hsWarned.contains(iID)) {
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.housingWarn"), ((Citizen) le).getCitizenData().getFullName()), ColorGL.ORANGE, new Point3DShort(le.getX(), le.getY(), le.getZ()), le.getID()); //$NON-NLS-1$
            } else if (leaver == null) {
                leaver = (Citizen) le;
            }
        }
        hsWarned.clear();
        hsWarned.addAll(hsNow);
        if (leaver != null && !bSomeoneLeft) {
            hsWarned.remove(Integer.valueOf(leaver.getID()));
            String sName = leaver.getCitizenData().getFullName();
            Point3DShort p3d = new Point3DShort(leaver.getX(), leaver.getY(), leaver.getZ());
            leaver.deleteLeaving();
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + sName + " left: no roofed bed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.housingQuit"), sName), ColorGL.RED, p3d); //$NON-NLS-1$
        }
    }

    /**
     * @return true when the citizen has a bunk room of their own with a bed
     *         under a roof
     */
    public static boolean hasRoofedBed(Citizen cit) {
        int iZone = cit.getCitizenData().getZoneID();
        Zone zone = iZone > 0 ? Zone.getZone(iZone) : null;
        if (zone == null) {
            return false;
        }
        for (Point3DShort p : zone.getPoints()) {
            Item item = World.getCell(p.x, p.y, p.z).getItem();
            ItemManagerItem imi = item == null ? null : ItemManager.getItem(item.getIniHeader());
            if (imi != null && imi.canBeUsedToSleep() && Weather.isUnderRoof(p.x, p.y, p.z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the orange row's part for the crew without a roofed bed, or
     *         null: from WARN_FROM_DAY, with when they start leaving before
     *         HOUSING_FROM_DAY
     */
    static String getStatus(boolean bTutorial) {
        int iDay = Game.getWorld().getDate().getDaysSinceStart();
        if (bTutorial || iDay < WARN_FROM_DAY) {
            return null;
        }
        int n = 0;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen && !hasRoofedBed((Citizen) le)) {
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        return MessageFormat.format(Messages.getString(iDay < HOUSING_FROM_DAY ? "DataCenter.statusHousingSoon" : "DataCenter.statusHousing"), n, HOUSING_FROM_DAY); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
