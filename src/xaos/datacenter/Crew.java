package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import xaos.actions.ActionPriorityManager;
import xaos.main.Game;
import xaos.main.World;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.Messages;
import xaos.utils.Utils;
import xaos.utils.UtilsIniHeaders;

/**
 * The crew's two roles (Patrick, 2026-10-31: "make 5 technicians and 5
 * construction workers. technicians can't build and construction works
 * can't fix the servers. maybe more workers and less technicians?").
 *
 * A role is the living's type: technicians are citizenmale and
 * citizenfemale, construction workers buildermale and builderfemale
 * (livingentities.xml), so it is saved with the living.  Technicians take
 * the data center's jobs (priority datacenter: installing gear, the Parted
 * Magic tools, Repair, erasing) and never the building ones; construction
 * workers take building, digging and chopping and never the data center's.
 * Both haul, trade, eat and sleep.  TaskManager asks isJobDenied before it
 * hands a citizen a job, and the jobs panel leaves out what a role may not
 * take.
 *
 * A new site starts with START_TECHNICIANS technicians and the rest of the
 * mission's numCitizens as construction workers: a played site kept fewer
 * than one of its 11 technicians on a data center job on average, and at
 * most 4 or 5 at once (BalanceProbe, 2026-10-31), while the building work
 * comes all at once.  A newcomer takes the role short of that share.
 *
 * Saves older than Game.SAVEGAME_CREW have only technicians, who did
 * everything; roles are off for them, so they still play.
 *
 * Since 2026-11-01 a third role: the security guard (GUARD_TYPE), hired by
 * a guard post (Protests), never by the crew's newcomers.  He takes no job
 * and hauls nothing; he eats, sleeps and needs a bed like the rest, and
 * guards the site the rest of the time.
 */
public final class Crew {

    private static final String[] TECHNICIAN_TYPES = {"citizenmale", "citizenfemale"}; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String[] BUILDER_TYPES = {"buildermale", "builderfemale"}; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String BUILDER_PREFIX = "builder"; //$NON-NLS-1$
    public static final String GUARD_TYPE = "dcguard"; //$NON-NLS-1$

    public static final int START_TECHNICIANS = 4;
    // The technicians' share a newcomer keeps the crew near, in percent
    private static final int TECHNICIAN_SHARE_PCT = 40;

    public static final String PRIORITY_DATACENTER = "datacenter"; //$NON-NLS-1$
    private static final String[] BUILDING_PRIORITIES = {"itemconstruction", "wallconstruction", "terrainchange", ActionPriorityManager.PRIORITY_MINE_DIG}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static boolean rolesOn = true;
    private static int iDataCenterJob = Integer.MIN_VALUE;
    private static int[] aBuildingJobs;

    private Crew() {
    }

    /**
     * A new game: roles on
     */
    static void reset() {
        rolesOn = true;
    }

    /**
     * Before a load: a save older than Game.SAVEGAME_CREW has no roles
     */
    public static void setLoadingVersion(int iVersion) {
        rolesOn = iVersion >= Game.SAVEGAME_CREW;
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeBoolean(rolesOn);
    }

    static void readExternal(ObjectInput in) throws IOException {
        rolesOn = in.readBoolean();
    }

    public static boolean isBuilder(LivingEntity le) {
        return le != null && le.getIniHeader() != null && le.getIniHeader().startsWith(BUILDER_PREFIX);
    }

    public static boolean isGuard(LivingEntity le) {
        return le != null && GUARD_TYPE.equals(le.getIniHeader());
    }

    /**
     * @return the role's name for tooltips and lists
     */
    public static String getRoleName(LivingEntity le) {
        return Messages.getString(isGuard(le) ? "DataCenter.roleGuard" : isBuilder(le) ? "DataCenter.roleBuilder" : "DataCenter.roleTechnician"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * @return true when the citizen may not take a job of this priority,
     *         for its role or because the player turned it off
     */
    public static boolean isJobDenied(Citizen cit, String sJob) {
        return isJobDenied(cit, UtilsIniHeaders.getIntIniHeader(sJob));
    }

    public static boolean isJobDenied(Citizen cit, int iJob) {
        return isForbidden(cit, iJob) || cit.getCitizenData().containsDeniedJob(iJob);
    }

    /**
     * @return true when the citizen's role rules out jobs of this priority
     */
    public static boolean isForbidden(Citizen cit, String sJob) {
        return isForbidden(cit, UtilsIniHeaders.getIntIniHeader(sJob));
    }

    private static boolean isForbidden(Citizen cit, int iJob) {
        if (cit == null) {
            return false;
        }
        // A guard takes no job of any kind, in an older save too
        if (isGuard(cit)) {
            return true;
        }
        if (!rolesOn) {
            return false;
        }
        if (aBuildingJobs == null) {
            iDataCenterJob = UtilsIniHeaders.getIntIniHeader(PRIORITY_DATACENTER);
            aBuildingJobs = new int[BUILDING_PRIORITIES.length];
            for (int i = 0; i < BUILDING_PRIORITIES.length; i++) {
                aBuildingJobs[i] = UtilsIniHeaders.getIntIniHeader(BUILDING_PRIORITIES[i]);
            }
        }
        if (isBuilder(cit)) {
            return iJob == iDataCenterJob;
        }
        for (int iBuilding : aBuildingJobs) {
            if (iJob == iBuilding) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param iIndex the citizen's place among the new site's crew
     * @return the type to make: the first START_TECHNICIANS technicians,
     *         then construction workers, either sex at random
     */
    public static String getStartingType(int iIndex) {
        return pick(iIndex < START_TECHNICIANS ? TECHNICIAN_TYPES : BUILDER_TYPES);
    }

    /**
     * @return the type for a newcomer: the role short of the technicians'
     *         share, or a technician when roles are off (an older save)
     */
    public static String getNewcomerType() {
        if (!rolesOn) {
            return pick(TECHNICIAN_TYPES);
        }
        int iTechnicians = 0, iAll = 0;
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (isGuard(le)) {
                continue;
            }
            iAll++;
            if (!isBuilder(le)) {
                iTechnicians++;
            }
        }
        boolean bTechnician = iTechnicians < Math.round(TECHNICIAN_SHARE_PCT * (iAll + 1) / 100.0);
        return pick(bTechnician ? TECHNICIAN_TYPES : BUILDER_TYPES);
    }

    /**
     * @return {technicians, construction workers, guards} on the site now
     */
    public static int[] getCounts() {
        int[] a = new int[3];
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            a[isGuard(le) ? 2 : isBuilder(le) ? 1 : 0]++;
        }
        return a;
    }

    // One game die, as the random citizen type it replaces drew one
    private static String pick(String[] aTypes) {
        return aTypes[Utils.getRandomBetween(0, aTypes.length - 1)];
    }
}
