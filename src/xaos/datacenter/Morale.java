package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.HashSet;

import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;

/**
 * Morale (docs/stakes.md, the stakes pass of 2026-10-30): a technician who
 * stays miserable quits.
 *
 * At dawn a technician below QUIT_BELOW happiness is warned in the log; of
 * those warned at an earlier dawn and still below it, the unhappiest quits
 * and leaves the site (one a dawn, so a crew that sat at 0 in a site with no
 * break room goes one by one, not all at once), and the rest lose a fifth of
 * their happiness (LivingEntity.delete, Towns' rule for a citizen gone), so
 * neglect snowballs.  A happy site with free bunk rooms
 * draws new technicians (World.checkImmigrants, Towns' own).  What moves
 * happiness is Towns' too: a night in a cot (and, since this pass, a night
 * on the floor costs some), idling outside a break room or working too long,
 * eating in the break room, furniture and planters in sight.
 *
 * Nobody is warned or quits before FROM_DAY, the day the roofed-bed rule
 * starts (Housing; Patrick, 2026-10-31, "Yes, from day 10"): the first ten
 * days are for setting up.  With the crew split, technicians idling with
 * nothing to fix were the first to go, and a site whose technicians had all
 * quit could not repair its grid feed and starved by day 10.
 */
public final class Morale {

    static final int QUIT_BELOW = 15;
    static final int FROM_DAY = Housing.HOUSING_FROM_DAY;
    static final int UNHAPPY_BELOW = 25;
    private static final int REASONS = 6;

    // Technicians warned at the last dawn, by living ID
    private static final HashSet<Integer> hsWarned = new HashSet<Integer>();

    private Morale() {
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
     * Dawn, from FROM_DAY: the miserable are warned, and the unhappiest of
     * those warned before who are still miserable quits (not in a tutorial)
     *
     * @return true when someone quit (Housing then sends nobody away)
     */
    static boolean newDay(boolean bTutorial) {
        if (bTutorial || Game.getWorld().getDate().getDaysSinceStart() < FROM_DAY) {
            hsWarned.clear();
            return false;
        }
        Citizen quitter = null;
        HashSet<Integer> hsNow = new HashSet<Integer>();
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (!(le instanceof Citizen)) {
                continue;
            }
            int iHappiness = ((Citizen) le).getCitizenData().getHappiness();
            if (iHappiness >= QUIT_BELOW) {
                continue;
            }
            hsNow.add(iID);
            if (!hsWarned.contains(iID)) {
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.moraleWarn"), ((Citizen) le).getCitizenData().getFullName()), ColorGL.ORANGE, new Point3DShort(le.getX(), le.getY(), le.getZ()), le.getID()); //$NON-NLS-1$
            } else if (quitter == null || iHappiness < quitter.getCitizenData().getHappiness()) {
                quitter = (Citizen) le;
            }
        }
        hsWarned.clear();
        hsWarned.addAll(hsNow);
        if (quitter != null) {
            hsWarned.remove(Integer.valueOf(quitter.getID()));
            quit(quitter);
            return true;
        }
        return false;
    }

    private static void quit(Citizen cit) {
        String sName = cit.getCitizenData().getFullName();
        Point3DShort p3d = new Point3DShort(cit.getX(), cit.getY(), cit.getZ());
        String sReason = Messages.getString("DataCenter.quitReason" + (cit.getID() % REASONS)); //$NON-NLS-1$
        cit.deleteLeaving();
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + sName + " quit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.moraleQuit"), sName, sReason), ColorGL.RED, p3d); //$NON-NLS-1$
    }

    /**
     * @return the average happiness of the technicians, 0 to 100
     */
    static int getMorale() {
        return World.getHappinessAverage();
    }

    /**
     * @return the technicians below UNHAPPY_BELOW
     */
    static int getUnhappy() {
        int n = 0;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen && ((Citizen) le).getCitizenData().getHappiness() < UNHAPPY_BELOW) {
                n++;
            }
        }
        return n;
    }

    /**
     * @return who has run out of food and not found any.  They stop working
     *         until they eat, which nothing else on the screen says
     */
    static int getHungry() {
        int n = 0;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen && ((Citizen) le).getCitizenData().getHungry() == 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * @return the technicians warned at a dawn who are still here and may
     *         quit at the next one (one of them will, if still miserable)
     */
    static int getQuitting() {
        int n = 0;
        for (Integer iID : hsWarned) {
            if (World.getCitizenIDs().contains(iID)) {
                n++;
            }
        }
        return n;
    }
}
