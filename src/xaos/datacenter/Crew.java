package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import xaos.actions.ActionPriorityManager;
import xaos.main.Game;
import xaos.main.World;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntity;
import xaos.tiles.entities.living.LivingEntityManager;
import xaos.utils.Messages;
import xaos.utils.Names;
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
 * Since 2026-11-01 a third role: the security guard (GUARD_TYPES), hired by
 * a guard post (Protests), never by the crew's newcomers.  He takes no job
 * and hauls nothing; he eats, sleeps and needs a bed like the rest, and
 * guards the site the rest of the time.
 *
 * Nobody on the site looks like anyone else while a look is left (Patrick,
 * 2026-11-04, after the protesters: more looks for the crew): a new crew
 * member takes a look nobody wears, of either sex when one has run out
 * (pickLook, pick, getGuardType).  There are 16 for technicians, 24 for
 * construction workers and 3 for guards, enough for a crew of 40 and three
 * guards at the technicians' share.  Past that the least worn look repeats.
 *
 * Everybody has a spoof tech name, no two alike on a site (Patrick, 2026-11-04:
 * "everybody needs a spoof tech bro name like Ellen Tusk or Sam Alt-Delete"):
 * whole names in names.xml, pickName.
 */
public final class Crew {

    private static final String[] TECHNICIAN_TYPES = {"citizenmale", "citizenfemale"}; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String[] BUILDER_TYPES = {"buildermale", "builderfemale"}; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String BUILDER_PREFIX = "builder"; //$NON-NLS-1$
    private static final String GUARD_PREFIX = "dcguard"; //$NON-NLS-1$
    private static final String[] GUARD_TYPES = {"dcguard", "dcguard2", "dcguard3"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    // When a pool's names are all taken they come round again with these
    private static final String[] NAME_SUFFIXES = {"", " Jr.", " III", " IV", " V"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    public static final int START_TECHNICIANS = 4;
    // The technicians' share a newcomer keeps the crew near, in percent
    private static final int TECHNICIAN_SHARE_PCT = 40;

    public static final String PRIORITY_DATACENTER = "datacenter"; //$NON-NLS-1$
    private static final String[] BUILDING_PRIORITIES = {"itemconstruction", "wallconstruction", "terrainchange", ActionPriorityManager.PRIORITY_MINE_DIG}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static boolean rolesOn = true;
    // The looks and names picked for a new site's crew: they join the citizen
    // list only when the game starts (World.generateCitizens makes them without
    // init)
    private static ArrayList<String> alStartingLooks;
    private static ArrayList<String> alStartingNames;
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
        return le != null && le.getIniHeader() != null && le.getIniHeader().startsWith(GUARD_PREFIX);
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

    /**
     * Around the making of a new site's crew: their looks count as worn and
     * their names as taken
     */
    public static void startStartingCrew() {
        alStartingLooks = new ArrayList<String>();
        alStartingNames = new ArrayList<String>();
    }

    public static void endStartingCrew() {
        alStartingLooks = null;
        alStartingNames = null;
    }

    /**
     * @param sPool a crew type's pool in names.xml
     * @return a name from it nobody on the site has, at random, one game die,
     *         or null when there is no such pool
     */
    public static String pickName(String sPool) {
        ArrayList<String> alNames = Names.getNames(sPool, Game.getWorld().getCampaignID(), Game.getWorld().getMissionID());
        if (alNames == null || alNames.isEmpty()) {
            return null;
        }
        HashSet<String> hsTaken = new HashSet<String>();
        if (alStartingNames != null) {
            hsTaken.addAll(alStartingNames);
        }
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le instanceof Citizen) {
                hsTaken.add(((Citizen) le).getCitizenData().getFullName());
            }
        }
        String sName = null;
        for (int i = 0; i < NAME_SUFFIXES.length && sName == null; i++) {
            ArrayList<String> alFree = new ArrayList<String>();
            for (String s : alNames) {
                if (!hsTaken.contains(s + NAME_SUFFIXES[i])) {
                    alFree.add(s + NAME_SUFFIXES[i]);
                }
            }
            if (!alFree.isEmpty()) {
                sName = alFree.get(Utils.getRandomBetween(0, alFree.size() - 1));
            }
        }
        if (sName == null) {
            sName = alNames.get(Utils.getRandomBetween(0, alNames.size() - 1));
        }
        if (alStartingNames != null) {
            alStartingNames.add(sName);
        }
        return sName;
    }

    /**
     * @param sIniHeader a crew type with alternative looks
     * @param alAlt its alternative looks
     * @return one of its looks that fewest people on the site wear, at random
     *         among those, one game die, as LivingEntity.getAltGraphics drew
     */
    public static String pickLook(String sIniHeader, ArrayList<String> alAlt) {
        ArrayList<String> alLooks = new ArrayList<String>(alAlt);
        alLooks.add(sIniHeader);
        String sLook = pickLeastWorn(alLooks);
        if (alStartingLooks != null) {
            alStartingLooks.add(sLook);
        }
        return sLook;
    }

    /**
     * @return the guard type fewest guards on the site wear, one game die
     */
    public static String getGuardType() {
        return pickLeastWorn(Arrays.asList(GUARD_TYPES));
    }

    private static String pickLeastWorn(List<String> alLooks) {
        HashMap<String, Integer> hmWorn = getWornLooks();
        int iFewest = Integer.MAX_VALUE;
        for (String s : alLooks) {
            iFewest = Math.min(iFewest, hmWorn.getOrDefault(s, 0));
        }
        ArrayList<String> alLeast = new ArrayList<String>();
        for (String s : alLooks) {
            if (hmWorn.getOrDefault(s, 0) == iFewest) {
                alLeast.add(s);
            }
        }
        return alLeast.get(Utils.getRandomBetween(0, alLeast.size() - 1));
    }

    private static HashMap<String, Integer> getWornLooks() {
        HashMap<String, Integer> hmWorn = new HashMap<String, Integer>();
        for (Integer iID : World.getCitizenIDs()) {
            LivingEntity le = World.getLivingEntityByID(iID.intValue());
            if (le != null) {
                hmWorn.merge(le.getIniHeader(), 1, Integer::sum);
            }
        }
        if (alStartingLooks != null) {
            for (String s : alStartingLooks) {
                hmWorn.merge(s, 1, Integer::sum);
            }
        }
        return hmWorn;
    }

    private static boolean hasFreeLook(String sType) {
        HashMap<String, Integer> hmWorn = getWornLooks();
        if (!hmWorn.containsKey(sType)) {
            return true;
        }
        for (String s : LivingEntityManager.getItem(sType).getAltGraphics()) {
            if (!hmWorn.containsKey(s)) {
                return true;
            }
        }
        return false;
    }

    // One game die, as the random citizen type it replaces drew one; the other
    // sex when this one's looks are all worn and the other's are not
    private static String pick(String[] aTypes) {
        String sType = aTypes[Utils.getRandomBetween(0, aTypes.length - 1)];
        if (!hasFreeLook(sType)) {
            for (String s : aTypes) {
                if (hasFreeLook(s)) {
                    return s;
                }
            }
        }
        return sType;
    }
}
