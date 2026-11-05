package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.TreeMap;

import xaos.actions.ActionManager;
import xaos.actions.ActionManagerItem;
import xaos.campaign.MissionData;
import xaos.data.EventData;
import xaos.events.EventManager;
import xaos.events.EventManagerItem;
import xaos.main.Game;
import xaos.main.World;
import xaos.panels.CommandPanel;
import xaos.panels.ImagesPanel;
import xaos.panels.MessagesPanel;
import xaos.tiles.Cell;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.terrain.Terrain;
import xaos.utils.ColorGL;
import xaos.utils.Log;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;
import xaos.utils.UtilsIniHeaders;

/**
 * The data center rules (docs/rules.md in the game folder).
 *
 * Every turn the power sources are added up: the grid (nothing in an
 * outage), generators, solar panels in daylight, and batteries in an
 * outage.  Power goes first to the cooling units, switches and the other
 * machines (those are off in an outage), then to the racks with a customer
 * and then the empty ones, each in the order they were installed (a
 * swapped rack counts as new), and last to the batteries that are not full.
 * A rack runs when power, cooling and a network port are all left for it;
 * the map draws the red cross on a rack that cannot run (isDown).
 * A fiber cut or a DNS failure (Network) leaves the racks running and out of
 * the customers' reach, which earns nothing and wears the red cross too.
 * Protests (from day 7) wreck racks, switches and the grid feed: the
 * Protests class runs them, and this one keeps the wrecked gear down until a
 * Repair puts it back.
 * Machines without power are set not operative, which stops the erase
 * station's jobs and the food machines.  At dawn the customers pay for the
 * hours their racks ran.
 *
 * Drives wear out through the items' own aging (maxAge in items.xml): a
 * rack becomes worn, then dead; Drive Grader turns a worn rack into a
 * flagged one, Clone and Rescue put a healthy rack back (actions.xml).  This
 * class speeds the wear up in a hot hall and keeps down racks down.
 *
 * At dawn a running rack whose type has dcEventPCT may get a service event:
 * it is swapped for a rack that will not boot, has malware or has locked its
 * customer out, and stays down until Boot Repair, the Virus Scanner or
 * Password Reset puts a healthy rack back.  Those dice are the only random
 * numbers drawn here.
 *
 * At dawn, before the pay, the bills: a coin per kW of the day's average
 * draw from the grid, DIESEL_PRICE per kW of the generators' (they burn
 * diesel, since 2026-11-01) and a coin a day for every GENERATOR_KW_A_COIN
 * kW a generator can give, and a coin per technician.  Solar comes first and
 * is free, then the grid's 40 kW, then the generators.  Three dawns in a row
 * below zero coins and the site is lost (not in the tutorial).
 *
 * A rack installed on a site starts empty (Patrick, 2026-11-01): it runs but
 * earns nothing until a customer signs (at dawn, a chance of the reputation
 * and SIGN_BASE in 200).  Only the starting kit's boxed racks have customers
 * waiting for them.  A rack keeps its customer until it is down a full day,
 * and then it is an empty rack again.  Reputation runs 0 to
 * 100 from 50: up at a dawn by how much of the day's pay the racks earned,
 * down for each customer lost and for bad news (Protests, MoveOuts).  It no longer loses
 * the site at 0: a protest took a site from 42 to 0 in a day (rework play,
 * 2026-10-30); money and crew are the ways to lose.  In the tutorial
 * customers do not leave.
 * Racks are known by cell (itemKey): a job, a service event or wear swaps a
 * rack for a new item with a new ID.
 *
 * The day's power draw and the count of dawns below zero are saved with
 * the game (writeExternal, from Game.SAVEGAME_BILLS); the rest is worked out
 * again from the installed items every turn.  A lost site is not saved: the
 * autosave skips it (World), so loading goes back to a dawn before the loss.
 */
public final class DataCenter {

    // Why a rack is down: dcDown in items.xml
    public static final int DOWN_DRIVE = 1;
    public static final int DOWN_BOOT = 2;
    public static final int DOWN_MALWARE = 3;
    public static final int DOWN_LOCKED = 4;
    public static final int DOWN_WRECKED = 5;

    private static final int REASON_POWER = 0;
    private static final int REASON_COOLING = 1;
    private static final int REASON_NETWORK = 2;
    private static final int REASON_DRIVE = 3;  // REASON_DRIVE + dcDown - 1 for every dcDown
    // Running, but the customers cannot reach it (Network).  Not in racksDown:
    // the log and the orange row say why in their own words
    private static final int REASON_OFFLINE = -2;

    // The log's words for each reason, and the status line's for the dcDown ones
    private static final String[] DOWN_KEYS = {
        "DataCenter.downPower", "DataCenter.downCooling", "DataCenter.downNetwork", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "DataCenter.downDrive", "DataCenter.downBoot", "DataCenter.downVirus", "DataCenter.downLocked", "DataCenter.downWrecked" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };
    private static final String[] STATUS_KEYS = {
        "DataCenter.dead", "DataCenter.boot", "DataCenter.virus", "DataCenter.locked", "DataCenter.wrecked" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    // The service events, and what the log says when one happens
    private static final String[] EVENT_RACKS = {"dcrackboot", "dcrackvirus", "dcracklocked"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final String[] EVENT_KEYS = {"DataCenter.eventBoot", "DataCenter.eventVirus", "DataCenter.eventLocked"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    // Above this share of the cooling, running racks' drives age twice as fast
    private static final int HEAT_WEAR_PCT = 80;

    // The bills at dawn (docs/stakes.md, step 1): coins per kW of the day's
    // average draw and per technician, and the dawns in a row below zero
    // coins that lose the site
    private static final int POWER_PRICE = 1;
    private static final int TECHNICIAN_PAY = 1;
    private static final int BROKE_DAWNS = 3;
    // Generators burn diesel (Patrick picked it, 2026-11-01: in the big play
    // 7 generators ran 170 kW at the grid's price): coins per kW of their
    // day's average draw, and a coin a day for every GENERATOR_KW_A_COIN kW
    // a generator can give, to keep it fueled and ready (3 for the diesel
    // generator, 1 for the small one)
    private static final int DIESEL_PRICE = 2;
    private static final int GENERATOR_KW_A_COIN = 10;

    // Customers and reputation (docs/stakes.md, step 2)
    private static final int REPUTATION_START = 50;
    private static final int REPUTATION_MAX = 100;
    // A dawn raises the reputation by how much of the day's pay the racks
    // earned: since 2026-11-04, when every rack had to run every turn, one
    // service call spoiled the day and busy sites only ever went down
    private static final int REPUTATION_GOOD_DAY = 2;
    private static final int REPUTATION_FAIR_DAY = 1;
    private static final int UPTIME_GOOD_PCT = 95;
    private static final int UPTIME_FAIR_PCT = 75;
    private static final int REPUTATION_CUSTOMER_LOST = 5;
    // A wreck that made the news (Protests), and the level the status warns below
    private static final int REPUTATION_NEWS = 2;
    private static final int REPUTATION_LOW = 25;
    // An empty rack signs at dawn with a chance of (reputation + SIGN_BASE)
    // in SIGN_DIE: 10% at 0, 35% at 50, 60% at 100
    private static final int SIGN_BASE = 20;
    private static final int SIGN_DIE = 200;
    // What an empty rack draws and heats while it idles
    private static final int EMPTY_RACK_KW = 1;

    // Power sources, dcSource in items.xml: on all the time (generators), the
    // grid (nothing in an outage), solar (daylight only, not billed), and
    // batteries (only in an outage; they charge from what is left otherwise)
    public static final int SOURCE_ALWAYS = 0;
    public static final int SOURCE_GRID = 1;
    public static final int SOURCE_SOLAR = 2;
    public static final int SOURCE_BATTERY = 3;

    // Outages and batteries (docs/stakes.md, step 3): from day 5 each dawn
    // may set a grid outage for that day, 2 to 8 hours from a random hour; a
    // battery carries its kW for 4 hours and charges at 2 kW
    private static final int OUTAGE_FROM_DAY = 5;
    private static final int OUTAGE_PCT = 25;
    private static final int BATTERY_HOURS = 4;
    private static final int BATTERY_CHARGE_KW = 2;

    // Water cooling, dcWater in items.xml: a water-cooled unit cools only
    // through a line of pipes (or straight from beside it) to a running pump,
    // and a pump runs only next to water
    public static final int WATER_UNIT = 1;
    public static final int WATER_PUMP = 2;
    public static final int WATER_PIPE = 3;

    // Security, dcSecurity in items.xml: a camera (Protests calls the guard
    // when it sees a protester) and a guard post (Protests hires its guard)
    public static final int SECURITY_CAMERA = 1;
    public static final int SECURITY_POST = 2;

    // Heat waves (docs/stakes.md, step 4): from day 4 a dawn starts one about
    // one day in seven, for 1 to 2 days.  It is Towns' "hot" event
    // (events.xml), so the map shows it and the technicians work slower; air
    // cooling units cool 30% less while it lasts
    private static final String HEAT_WAVE_EVENT = "hot"; //$NON-NLS-1$
    private static final int HEAT_WAVE_FROM_DAY = 4;
    private static final int HEAT_WAVE_PCT = 14;
    private static final int HEAT_WAVE_AIR_PCT = 70;

    // What a new map starts with, placed around the citizens' arrival point
    // The cleared lot around the arrival point, and what is cleared from it
    private static final int CLEARED_RADIUS = 8;
    private static final java.util.Set<String> TREES = new HashSet<String>(java.util.Arrays.asList(
            "dctree", "dcpine", "dcstump", "dcpinestump", "dcsapling", "dcpinesapling")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private static final String[] STARTING_KIT = {
        "dcgrid", "dcswitch", "dccooler", "dcrack", "dcrack", "dcrackkit", "dcrackkit", "dccoolerkit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "dcfiber", "dcdns", "dcoldserver", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "dcerasekit", "dcguardpostkit", "dcdrive", "dcdrive", "dcparts", "dcparts", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "dcvending", "dccoffeemachine", //$NON-NLS-1$ //$NON-NLS-2$
        "dcsnack", "dcsnack", "dcsnack", "dcsnack", "dccoffee", "dccoffee", "dccoffee", "dccoffee", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "dcconcrete", "dcconcrete", "dcconcrete", "dcconcrete", "dcconcrete", "dcconcrete", "dcconcrete", "dcconcrete", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "dcsteel", "dcsteel", "dcsteel", "dcsteel", "dcsteel", "dcsteel" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    };
    // The starting kit's boxed racks come with their customers, so the first
    // days play as they did before new racks started empty
    private static final int KIT_CUSTOMERS = (int) java.util.Arrays.stream(STARTING_KIT).filter("dcrackkit"::equals).count(); //$NON-NLS-1$

    private static final Comparator<Item> BY_INSTALL_ORDER = new Comparator<Item>() {
        public int compare(Item a, Item b) {
            return Integer.compare(a.getID(), b.getID());
        }
    };

    // Racks with a customer first, so when power, cooling or ports run short
    // the empty racks go without.  A rack a job or wear swapped is a new item
    // with a new ID, so on install order alone a worn paying rack lost its
    // cooling to an empty one installed after it
    private static final Comparator<Item> CUSTOMERS_FIRST = new Comparator<Item>() {
        public int compare(Item a, Item b) {
            boolean bEmptyA = hsEmptyRacks.contains(itemKey(a));
            boolean bEmptyB = hsEmptyRacks.contains(itemKey(b));
            if (bEmptyA != bEmptyB) {
                return bEmptyA ? 1 : -1;
            }
            return Integer.compare(a.getID(), b.getID());
        }
    };

    private static int powerSupply;
    private static int powerUsed;
    private static int coolingCapacity;
    private static int heat;
    private static int ports;
    private static int portsUsed;
    private static int racks;
    private static int racksOnline;
    private static int payPerDay;
    private static int customers;
    private static int racksFailing;
    private static final int[] racksDown = new int[REASON_DRIVE + DOWN_WRECKED];
    private static String lastDownReport = "";

    // The day's draw from the grid and from the generators, and the pay,
    // added up turn by turn for the dawn: the bill goes by the day's average
    // draw, and customers pay for the hours their racks ran.  A save from
    // before diesel has only the first, with the generators' draw in it
    private static long powerUsedToday;
    private static long dieselUsedToday;
    private static long payToday;
    private static int turnsToday;
    // What the racks with customers would have earned today had none been
    // down, and that as a rate this turn; the dawn's uptime in percent
    private static long payFullToday;
    private static int payFullPerDay;
    private static int uptimePct = 100;

    // This turn: the billed draw, the batteries' charge and capacity in
    // kW-turns, and whether there is a grid feed.  The billed draw is the
    // grid's up to what it gives, then the generators' (gridPower,
    // dieselPower); generatorUpkeep is what the generators cost a day to
    // keep ready
    private static int billedPower;
    private static int gridPower;
    private static int dieselPower;
    private static int generatorUpkeep;
    private static int batteryCharge;
    private static int batteryCapacity;
    private static boolean hasGrid;

    // The grid outage: the turn of the day it starts (-1 when none is
    // coming), its length, and the turns left while it lasts
    private static int outageStart = -1;
    private static int outageLength;
    private static int outageLeft;

    // Each battery's charge in kW-turns, by cell (itemKey); a battery with
    // no entry is full
    private static final HashMap<Integer, Integer> hmBatteryCharge = new HashMap<Integer, Integer>();

    // This turn: pumps with no water beside them and water-cooled units with
    // no line to a running pump; whether a heat wave was on, for the log
    private static int pumpsDry;
    private static int unitsDry;
    private static boolean heatWave;

    // This turn: the running cooling, air (before a heat wave's cut, airPct)
    // and water; coolingCapacity is the two after the cut
    private static int airCooling;
    private static int waterCooling;
    private static int airPct = 100;

    // This turn: wrecked gear that is not a rack (a cut grid feed, a wrecked
    // switch), the guard posts, and the cameras that run
    private static int wreckedOther;
    private static final ArrayList<Item> alPosts = new ArrayList<Item>();
    private static final ArrayList<Item> alCameras = new ArrayList<Item>();

    // Ripe tomato planters, for Garden (found in updateHall's scan)
    private static final ArrayList<Item> alRipePlants = new ArrayList<Item>();

    // Dawns in a row that ended below zero coins, and whether the site is lost
    private static int brokeDawns;
    private static boolean siteLost;

    // Reputation; the racks with no customer and the turns in a row each
    // down rack has been down, by cell (itemKey); whether every rack ran
    // every turn since the last dawn
    private static int reputation = REPUTATION_START;
    private static final HashSet<Integer> hsEmptyRacks = new HashSet<Integer>();
    private static final HashMap<Integer, Integer> hmDownTurns = new HashMap<Integer, Integer>();
    private static boolean allRanToday = true;

    // Every installed rack's cell at the last look (noteNewRacks), so a rack
    // in any other cell is new; knownRacks is false until the first look at
    // a game just made or loaded.  The customers still waiting for a rack:
    // those of the starting kit's boxed racks
    private static final HashSet<Integer> hsKnownRacks = new HashSet<Integer>();
    private static boolean knownRacks;
    private static int waitingCustomers;

    // Racks that are not running, and wrecked gear (a switch or a cut grid
    // feed), by item ID.  Neither is set not operative: the jobs (Drive
    // Grader, Clone, Rescue, Repair and the rest) only walk to operative
    // items, so the map draws the red cross from these sets instead
    private static final HashSet<Integer> hsDownRacks = new HashSet<Integer>();
    // Why each rack in hsDownRacks is down (REASON_*), for the map tooltip
    private static final HashMap<Integer, Integer> hmDownReason = new HashMap<Integer, Integer>();
    // The tooltip's words for a rack down for want of power, cooling or a port
    private static final String[] HINT_KEYS = {"DataCenter.hintPower", "DataCenter.hintCooling", "DataCenter.hintNetwork"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final HashSet<Integer> hsWreckedGear = new HashSet<Integer>();

    // The racks that ran this turn, for the dawn's service events
    private static final ArrayList<Integer> alRunningRackIDs = new ArrayList<Integer>();

    // Every installed rack this turn, in install order
    private static final ArrayList<Item> alHallRacks = new ArrayList<Item>();
    // Gear lying loose at the last count (not installed: it does nothing)
    private static int looseGear;
    // The orange row counts the hungry from this many, or a third of the crew
    private static final int HUNGRY_SHOWN_MIN = 3;
    // Installed machines that put out food (thieves can take them all, and
    // two days later the crew has starved)
    private static int foodMachines;

    private DataCenter() {
    }

    /**
     * Works out which racks run this turn and logs a change in the racks
     * that are down.
     */
    public static void nextTurn() {
        MoveOuts.nextTurn(hsEmptyRacks);
        updateOutage();
        Network.nextTurn();
        reportHeatWave();
        ArrayList<Item> alRunning = updateHall(true);
        powerUsedToday += gridPower;
        dieselUsedToday += dieselPower;
        payToday += payPerDay;
        payFullToday += payFullPerDay;
        turnsToday++;
        watchCustomers();
        OldServer.nextTurn(alHallRacks, isTutorial());
        Protests.nextTurn(alHallRacks, alPosts, alCameras);
        Bear.nextTurn(isTutorial());
        Thieves.nextTurn(isTutorial());
        Buyout.nextTurn();
        Garden.nextTurn(alRipePlants);
        Roofs.nextTurn();
        Boxes.nextTurn();
        Supply.nextTurn();
        Weather.nextTurn();
        // Nobody left: no pay goes out and nothing gets done, so the site is
        // over (they starve when the food machines lose their power)
        if (!siteLost && World.getCitizenIDs().isEmpty() && !isTutorial()) {
            loseSite("DataCenter.lostCrew"); //$NON-NLS-1$
        }

        // A hot hall wears the running drives out twice as fast: one more
        // turn of age on top of the one every item gets
        if (heat * 100 > coolingCapacity * HEAT_WEAR_PCT) {
            for (Item rack : alRunning) {
                if (rack.getMaxAge() > 0) {
                    rack.setAge(rack.getAge() + 1);
                }
            }
        }

        reportDownRacks();
    }

    /**
     * Works the hall out for a game just made or loaded (Game.setupGame).
     * Until its first turn the status line and the red crosses showed the
     * last game played, and a loaded game can open paused.  The racks that
     * are down then are not logged again.
     */
    public static void refresh() {
        heatWave = isHeatWave();
        updateHall(false);
        lastDownReport = getDownReport();
        Protests.refresh();
        Bear.refresh();
        Weather.refresh();
    }

    /**
     * Clears the day's power draw and pay, the dawns below zero, the lost
     * site, the customers, the reputation, the outage and the batteries'
     * charge, for a new game (Game.setupGame) and before a load
     * (UtilsSavegame: an older save lacks some of them)
     */
    public static void reset() {
        Roofs.reset();
        Boxes.reset();
        powerUsedToday = 0;
        dieselUsedToday = 0;
        payToday = 0;
        payFullToday = 0;
        turnsToday = 0;
        outageStart = -1;
        outageLength = 0;
        outageLeft = 0;
        hmBatteryCharge.clear();
        brokeDawns = 0;
        siteLost = false;
        reputation = REPUTATION_START;
        hsEmptyRacks.clear();
        hmDownTurns.clear();
        allRanToday = true;
        hsKnownRacks.clear();
        knownRacks = false;
        waitingCustomers = KIT_CUSTOMERS;
        Protests.reset();
        Morale.reset();
        Supply.reset();
        Weather.reset();
        MoveOuts.reset();
        Crew.reset();
        Housing.reset();
        Leisure.reset();
        Bear.reset();
        Buyout.reset();
        Thieves.reset();
        Network.reset();
        OldServer.reset();
    }

    /**
     * Saves the day's power draw and the dawns below zero
     * (Game.SAVEGAME_BILLS), the reputation and the racks' customers
     * (Game.SAVEGAME_CUSTOMERS), the day's pay, the outage and the
     * batteries' charge (Game.SAVEGAME_POWER), then the protest and the
     * guards (Game.SAVEGAME_PROTESTS), then the technicians warned they will
     * quit (Game.SAVEGAME_MORALE), then the supplier's day, the coming rain
     * and the racks waiting to be wiped (Game.SAVEGAME_REWORK), then whether
     * the crew has roles and who was warned about their bed
     * (Game.SAVEGAME_CREW), then the bear (Game.SAVEGAME_BEAR) and the
     * thieves (Game.SAVEGAME_THIEVES), then the racks' cells at the last look
     * and the customers still waiting for a rack (Game.SAVEGAME_NEW_RACKS),
     * then the day's diesel draw (Game.SAVEGAME_DIESEL; the thieves' walls
     * and holes are in Thieves' part), then a fiber cut and a DNS failure
     * (Game.SAVEGAME_NETWORK), then the offers and the sale
     * (Game.SAVEGAME_BUYOUT).  A lost site is never saved, so its flag is not
     * either; a sold one neither.
     */
    public static void writeExternal(ObjectOutput out) throws IOException {
        out.writeLong(powerUsedToday);
        out.writeInt(turnsToday);
        out.writeInt(brokeDawns);
        out.writeInt(reputation);
        out.writeBoolean(allRanToday);
        out.writeInt(hsEmptyRacks.size());
        for (Integer iKey : hsEmptyRacks) {
            out.writeInt(iKey.intValue());
        }
        out.writeInt(hmDownTurns.size());
        for (Map.Entry<Integer, Integer> entry : hmDownTurns.entrySet()) {
            out.writeInt(entry.getKey().intValue());
            out.writeInt(entry.getValue().intValue());
        }
        out.writeLong(payToday);
        out.writeInt(outageStart);
        out.writeInt(outageLength);
        out.writeInt(outageLeft);
        out.writeInt(hmBatteryCharge.size());
        for (Map.Entry<Integer, Integer> entry : hmBatteryCharge.entrySet()) {
            out.writeInt(entry.getKey().intValue());
            out.writeInt(entry.getValue().intValue());
        }
        Protests.writeExternal(out);
        Morale.writeExternal(out);
        Supply.writeExternal(out);
        Weather.writeExternal(out);
        MoveOuts.writeExternal(out);
        Crew.writeExternal(out);
        Housing.writeExternal(out);
        Bear.writeExternal(out);
        Thieves.writeExternal(out);
        out.writeBoolean(knownRacks);
        out.writeInt(hsKnownRacks.size());
        for (Integer iKey : hsKnownRacks) {
            out.writeInt(iKey.intValue());
        }
        out.writeInt(waitingCustomers);
        out.writeLong(dieselUsedToday);
        Network.writeExternal(out);
        out.writeLong(payFullToday);
        Buyout.writeExternal(out);
    }

    /**
     * Loads what writeExternal saved, as far as the save's version has it
     */
    public static void readExternal(ObjectInput in) throws IOException {
        powerUsedToday = in.readLong();
        turnsToday = in.readInt();
        brokeDawns = in.readInt();
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_CUSTOMERS) {
            reputation = in.readInt();
            allRanToday = in.readBoolean();
            for (int i = in.readInt(); i > 0; i--) {
                hsEmptyRacks.add(Integer.valueOf(in.readInt()));
            }
            for (int i = in.readInt(); i > 0; i--) {
                int iKey = in.readInt();
                hmDownTurns.put(Integer.valueOf(iKey), Integer.valueOf(in.readInt()));
            }
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_POWER) {
            payToday = in.readLong();
            outageStart = in.readInt();
            outageLength = in.readInt();
            outageLeft = in.readInt();
            for (int i = in.readInt(); i > 0; i--) {
                int iKey = in.readInt();
                hmBatteryCharge.put(Integer.valueOf(iKey), Integer.valueOf(in.readInt()));
            }
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_PROTESTS) {
            Protests.readExternal(in);
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_MORALE) {
            Morale.readExternal(in);
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_REWORK) {
            Supply.readExternal(in);
            Weather.readExternal(in);
            MoveOuts.readExternal(in);
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_CREW) {
            Crew.readExternal(in);
            Housing.readExternal(in);
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_BEAR) {
            Bear.readExternal(in);
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_THIEVES) {
            Thieves.readExternal(in);
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_NEW_RACKS) {
            knownRacks = in.readBoolean();
            for (int i = in.readInt(); i > 0; i--) {
                hsKnownRacks.add(Integer.valueOf(in.readInt()));
            }
            waitingCustomers = in.readInt();
        } else {
            // Saved before new racks started empty: the racks there keep their
            // customers (the first look takes them as they are), and the next
            // rack installed starts empty
            waitingCustomers = 0;
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_DIESEL) {
            dieselUsedToday = in.readLong();
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_NETWORK) {
            Network.readExternal(in);
            payFullToday = in.readLong();
        }
        if (Game.SAVEGAME_LOADING_VERSION >= Game.SAVEGAME_BUYOUT) {
            Buyout.readExternal(in);
        }
    }

    /**
     * Power, cooling, ports and pay for the installed gear, in install order.
     * On a turn (not a refresh after a load) the batteries charge or give
     * power.
     *
     * @return the racks that run
     */
    private static ArrayList<Item> updateHall(boolean bTurn) {
        ArrayList<Item> alSources = new ArrayList<Item>();
        ArrayList<Item> alSupport = new ArrayList<Item>();
        ArrayList<Item> alRacks = new ArrayList<Item>();
        HashSet<Integer> hsPipes = new HashSet<Integer>();
        alPosts.clear();
        alCameras.clear();
        alRipePlants.clear();
        wreckedOther = 0;
        looseGear = 0;
        foodMachines = 0;
        Network.startLook();
        OldServer.startLook();
        for (Item item : World.getItems().values()) {
            ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
            if (imi != null && isGear(imi) && liesLoose(item)) {
                looseGear++;
            }
            if (imi != null && imi.getDcNet() > 0 && isInstalled(item)) {
                Network.note(item, imi.getDcNet());
                continue;
            }
            if (OldServer.ITEM.equals(item.getIniHeader()) && isInstalled(item)) {
                OldServer.note();
            }
            if (imi != null && imi.isDataCenter() && imi.getSpawn() != null && isInstalled(item)) {
                foodMachines++;
            }
            if (imi != null && imi.getDcWater() == WATER_PIPE && isInstalled(item)) {
                hsPipes.add(itemKey(item));
                continue;
            }
            if (imi != null && imi.getDcSecurity() == SECURITY_POST && isInstalled(item)) {
                alPosts.add(item);
                continue;
            }
            if (imi != null && imi.isDcPick() && isInstalled(item)) {
                alRipePlants.add(item);
                continue;
            }
            if (imi == null || !imi.isDataCenter() || !isInstalled(item)) {
                continue;
            }
            if (imi.getDcIncome() > 0) {
                alRacks.add(item);
            } else if (imi.getDcPower() > 0) {
                alSources.add(item);
            } else {
                alSupport.add(item);
            }
        }
        Collections.sort(alSources, BY_INSTALL_ORDER);
        Collections.sort(alSupport, BY_INSTALL_ORDER);
        Collections.sort(alRacks, BY_INSTALL_ORDER);
        noteNewRacks(alRacks);
        Collections.sort(alRacks, CUSTOMERS_FIRST);

        powerSupply = 0;
        powerUsed = 0;
        coolingCapacity = 0;
        heat = 0;
        ports = 0;
        portsUsed = 0;
        racksOnline = 0;
        payPerDay = 0;
        payFullPerDay = 0;
        customers = 0;
        racksFailing = 0;
        for (int i = 0; i < racksDown.length; i++) {
            racksDown[i] = 0;
        }
        hsDownRacks.clear();
        hmDownReason.clear();
        hsWreckedGear.clear();
        Network.endLook(hsWreckedGear, bTurn);
        boolean bOffline = Network.isOffline() && !Buyout.isSold();

        // What each source gives this turn.  Firm power (the grid and the
        // generators) is billed; solar is not, nor a battery's, which was
        // billed when it charged.  The generators burn diesel and cost a
        // little a day to keep ready
        boolean bOutage = outageLeft > 0;
        boolean bDaylight = isDaylight();
        int iSolar = 0;
        int iGrid = 0;
        int iBatteryOut = 0;
        boolean bGridFeed = false;
        ArrayList<Item> alBatteries = new ArrayList<Item>();
        batteryCharge = 0;
        batteryCapacity = 0;
        hasGrid = false;
        generatorUpkeep = 0;
        for (Item source : alSources) {
            ItemManagerItem imi = ItemManager.getItem(source.getIniHeader());
            setRunning(source, true);
            int iSource = imi.getDcSource();
            if (imi.isDcDown()) {
                // A cut grid feed gives nothing until it is repaired
                wreckedOther++;
                hsWreckedGear.add(Integer.valueOf(source.getID()));
                hasGrid = hasGrid || iSource == SOURCE_GRID;
                continue;
            }
            if (iSource == SOURCE_GRID) {
                hasGrid = true;
                bGridFeed = true;
                if (!bOutage) {
                    powerSupply += imi.getDcPower();
                    iGrid += imi.getDcPower();
                }
            } else if (iSource == SOURCE_SOLAR) {
                if (bDaylight) {
                    iSolar += imi.getDcPower();
                }
            } else if (iSource == SOURCE_BATTERY) {
                alBatteries.add(source);
                int iCapacity = getBatteryCapacity(imi);
                batteryCapacity += iCapacity;
                batteryCharge += getBatteryCharge(source, iCapacity);
            } else {
                powerSupply += imi.getDcPower();
                generatorUpkeep += Math.max(1, imi.getDcPower() / GENERATOR_KW_A_COIN);
            }
        }
        // Backup power: the grid gives nothing, in an outage or while its
        // feed is cut.  The batteries carry the hall then (a cut feed left
        // them full while the racks went down), and the racks come first
        boolean bBackup = bOutage || (hasGrid && !bGridFeed);
        if (bBackup) {
            for (Item battery : alBatteries) {
                ItemManagerItem imi = ItemManager.getItem(battery.getIniHeader());
                iBatteryOut += Math.min(imi.getDcPower(), getBatteryCharge(battery, getBatteryCapacity(imi)));
            }
        }
        int iFirm = powerSupply;
        powerSupply += iSolar + iBatteryOut;

        // On the grid the cooling units, switches and the other machines come
        // before the racks, in install order.  In an outage the other
        // machines (the erase station, the vending and coffee machines) are
        // off.  On backup power the cooling units, switches, pumps and
        // cameras wait (alSpare): each rack in turn starts the switch and
        // cooling it needs (startForRack), and what is left runs the rest.
        // With the cooling first, a battery smaller than the cooling ran no
        // rack at all (the play to day 30, day 14).  A pump runs only next to
        // water; the water-cooled units wait until the pumps are known; a
        // heat wave weakens the air cooling units
        airCooling = 0;
        waterCooling = 0;
        airPct = isHeatWave() ? HEAT_WAVE_AIR_PCT : 100;
        ArrayList<Item> alWaterUnits = new ArrayList<Item>();
        ArrayList<Item> alSpare = new ArrayList<Item>();
        ArrayList<Item> alPumpsAtWater = new ArrayList<Item>();
        HashSet<Integer> hsPumps = new HashSet<Integer>();
        HashSet<Integer> hsPumpsAtWater = new HashSet<Integer>();
        HashMap<Integer, ArrayList<Item>> hmFeeders = new HashMap<Integer, ArrayList<Item>>();
        pumpsDry = 0;
        for (Item support : alSupport) {
            ItemManagerItem imi = ItemManager.getItem(support.getIniHeader());
            if (imi.getDcWater() == WATER_UNIT) {
                alWaterUnits.add(support);
                if (bBackup) {
                    alSpare.add(support);
                }
                continue;
            }
            boolean bPump = imi.getDcWater() == WATER_PUMP;
            boolean bCamera = imi.getDcSecurity() == SECURITY_CAMERA;
            // The old server is wired so nothing turns it off but a technician
            boolean bNeeded = imi.getDcCooling() > 0 || imi.getDcPorts() > 0 || bPump || bCamera || OldServer.ITEM.equals(support.getIniHeader());
            boolean bCanRun = bNeeded || !bOutage;
            if (imi.isDcDown()) {
                // A wrecked switch is off until it is repaired, and stays
                // operative so the Repair job can reach it
                wreckedOther++;
                hsWreckedGear.add(Integer.valueOf(support.getID()));
                bCanRun = false;
            }
            if (bPump) {
                if (isNextToWater(support)) {
                    hsPumpsAtWater.add(itemKey(support));
                    alPumpsAtWater.add(support);
                } else {
                    pumpsDry++;
                    bCanRun = false;
                }
            }
            if (bCanRun && bNeeded && bBackup) {
                alSpare.add(support);
            } else if (bCanRun && powerUsed - imi.getDcPower() <= powerSupply) {
                startGear(support, hsPumps);
            } else {
                setRunning(support, imi.isDcDown());
            }
        }
        coolingCapacity = getCooling(airCooling, waterCooling);

        // Water-cooled units: each needs water, through a line of pipes from a
        // running pump or from a pump beside it, then power.  "No water" in
        // the status counts only the units with no line to a pump at water: a
        // pump without power (an outage) is a power matter.  On backup power
        // each unit keeps the pumps whose lines reach it (hmFeeders), so a
        // rack that needs the unit starts one of them too
        unitsDry = 0;
        if (!alWaterUnits.isEmpty()) {
            HashSet<Integer> hsLined = reachWater(hsPumpsAtWater, hsPipes);
            for (Item unit : alWaterUnits) {
                if (!touchesWater(unit, hsLined)) {
                    unitsDry++;
                }
            }
            if (bBackup) {
                for (Item pump : alPumpsAtWater) {
                    HashSet<Integer> hsOne = new HashSet<Integer>();
                    hsOne.add(itemKey(pump));
                    HashSet<Integer> hsReach = reachWater(hsOne, hsPipes);
                    for (Item unit : alWaterUnits) {
                        if (touchesWater(unit, hsReach)) {
                            Integer iID = Integer.valueOf(unit.getID());
                            if (!hmFeeders.containsKey(iID)) {
                                hmFeeders.put(iID, new ArrayList<Item>());
                            }
                            hmFeeders.get(iID).add(pump);
                        }
                    }
                }
            } else {
                HashSet<Integer> hsWater = hsPumps.size() == hsPumpsAtWater.size() ? hsLined : reachWater(hsPumps, hsPipes);
                for (Item unit : alWaterUnits) {
                    ItemManagerItem imi = ItemManager.getItem(unit.getIniHeader());
                    if (touchesWater(unit, hsWater) && powerUsed - imi.getDcPower() <= powerSupply) {
                        startGear(unit, hsPumps);
                    } else {
                        setRunning(unit, false);
                    }
                }
            }
        }

        ArrayList<Item> alRunning = new ArrayList<Item>();
        for (Item rack : alRacks) {
            ItemManagerItem imi = ItemManager.getItem(rack.getIniHeader());
            // Racks stay operative so the technicians' jobs can reach them
            setRunning(rack, true);
            boolean bCustomer = !hsEmptyRacks.contains(itemKey(rack));
            if (bCustomer) {
                customers++;
                payFullPerDay += imi.getDcIncome();
            }
            if (imi.isDcDown()) {
                // A dead drive or a service event: down until a tool fixes it
                int iDown = Math.max(DOWN_DRIVE, Math.min(DOWN_WRECKED, imi.getDcDown()));
                racksDown[REASON_DRIVE + iDown - 1]++;
                hsDownRacks.add(Integer.valueOf(rack.getID()));
                hmDownReason.put(Integer.valueOf(rack.getID()), Integer.valueOf(REASON_DRIVE + iDown - 1));
                continue;
            }
            if (imi.isDcFlagged()) {
                racksFailing++;
            }
            int iNeed = -imi.getDcPower();
            int iPorts = -Math.min(0, imi.getDcPorts());
            int iHeat = imi.getDcHeat();
            // An empty rack only idles: it drew full power, so a site that
            // lost its customers paid more than it earned while it waited
            // for new ones (the rework play: 30 a day against bills of 56)
            if (!bCustomer) {
                iNeed = Math.min(iNeed, EMPTY_RACK_KW);
                iHeat = Math.min(iHeat, EMPTY_RACK_KW);
            }
            int iReason = -1;
            if (powerUsed + iNeed > powerSupply) {
                iReason = REASON_POWER;
            } else if (bBackup) {
                iReason = startForRack(iNeed, iPorts, iHeat, alSpare, hmFeeders, hsPumps);
            } else if (portsUsed + iPorts > ports) {
                iReason = REASON_NETWORK;
            } else if (heat + iHeat > coolingCapacity) {
                iReason = REASON_COOLING;
            }
            if (iReason == -1) {
                powerUsed += iNeed;
                portsUsed += iPorts;
                heat += iHeat;
                alRunning.add(rack);
                if (bOffline) {
                    // It runs and draws power, and nobody can reach it
                    hsDownRacks.add(Integer.valueOf(rack.getID()));
                    hmDownReason.put(Integer.valueOf(rack.getID()), Integer.valueOf(REASON_OFFLINE));
                    continue;
                }
                if (bCustomer) {
                    payPerDay += imi.getDcIncome();
                }
                racksOnline++;
            } else {
                racksDown[iReason]++;
                hsDownRacks.add(Integer.valueOf(rack.getID()));
                hmDownReason.put(Integer.valueOf(rack.getID()), Integer.valueOf(iReason));
            }
        }
        racks = alRacks.size();
        alHallRacks.clear();
        alHallRacks.addAll(alRacks);

        // Backup power left after the racks runs the gear still waiting, in
        // install order, and then the water-cooled units the running pumps
        // reach
        for (Item gear : alSpare) {
            ItemManagerItem imi = ItemManager.getItem(gear.getIniHeader());
            if (imi.getDcWater() == WATER_UNIT) {
                continue;
            }
            if (powerUsed - imi.getDcPower() <= powerSupply) {
                startGear(gear, hsPumps);
            } else {
                setRunning(gear, false);
            }
        }
        HashSet<Integer> hsWater = alSpare.isEmpty() ? null : reachWater(hsPumps, hsPipes);
        for (Item unit : alSpare) {
            ItemManagerItem imi = ItemManager.getItem(unit.getIniHeader());
            if (imi.getDcWater() != WATER_UNIT) {
                continue;
            }
            if (touchesWater(unit, hsWater) && powerUsed - imi.getDcPower() <= powerSupply) {
                startGear(unit, hsPumps);
            } else {
                setRunning(unit, false);
            }
        }

        // Batteries: outside backup power each one below full takes 2 kW of
        // what is left after everything else; on backup power they give what
        // solar and the generators could not
        int[] aiBatteryChange = new int[alBatteries.size()];
        if (!bBackup) {
            for (int i = 0; i < alBatteries.size(); i++) {
                Item battery = alBatteries.get(i);
                int iCapacity = getBatteryCapacity(ItemManager.getItem(battery.getIniHeader()));
                if (getBatteryCharge(battery, iCapacity) < iCapacity && powerUsed + BATTERY_CHARGE_KW <= powerSupply) {
                    powerUsed += BATTERY_CHARGE_KW;
                    aiBatteryChange[i] = BATTERY_CHARGE_KW;
                }
            }
        }
        int iSolarUsed = Math.min(iSolar, powerUsed);
        int iBatteryUsed = bBackup ? Math.max(0, Math.min(iBatteryOut, powerUsed - iSolarUsed - iFirm)) : 0;
        billedPower = powerUsed - iSolarUsed - iBatteryUsed;
        // The grid's power first, the rest from the generators
        gridPower = Math.min(billedPower, iGrid);
        dieselPower = billedPower - gridPower;
        for (int i = 0; i < alBatteries.size() && iBatteryUsed > 0; i++) {
            ItemManagerItem imi = ItemManager.getItem(alBatteries.get(i).getIniHeader());
            int iOut = Math.min(iBatteryUsed, Math.min(imi.getDcPower(), getBatteryCharge(alBatteries.get(i), getBatteryCapacity(imi))));
            aiBatteryChange[i] = -iOut;
            iBatteryUsed -= iOut;
        }
        if (bTurn) {
            HashSet<Integer> hsKeys = new HashSet<Integer>();
            for (int i = 0; i < alBatteries.size(); i++) {
                Item battery = alBatteries.get(i);
                Integer iKey = itemKey(battery);
                hsKeys.add(iKey);
                if (aiBatteryChange[i] != 0) {
                    int iCapacity = getBatteryCapacity(ItemManager.getItem(battery.getIniHeader()));
                    int iCharge = Math.max(0, Math.min(iCapacity, getBatteryCharge(battery, iCapacity) + aiBatteryChange[i]));
                    hmBatteryCharge.put(iKey, Integer.valueOf(iCharge));
                    batteryCharge += aiBatteryChange[i];
                }
            }
            hmBatteryCharge.keySet().retainAll(hsKeys);
        }

        alRunningRackIDs.clear();
        for (Item rack : alRunning) {
            alRunningRackIDs.add(Integer.valueOf(rack.getID()));
        }
        return alRunning;
    }

    /**
     * Powers a cooling unit, switch, pump, camera or other machine this turn
     * (updateHall).
     */
    private static void startGear(Item gear, HashSet<Integer> hsPumps) {
        ItemManagerItem imi = ItemManager.getItem(gear.getIniHeader());
        powerUsed -= imi.getDcPower();
        if (imi.getDcWater() == WATER_UNIT) {
            waterCooling += imi.getDcCooling();
        } else {
            airCooling += imi.getDcCooling();
        }
        coolingCapacity = getCooling(airCooling, waterCooling);
        ports += Math.max(0, imi.getDcPorts());
        if (imi.getDcWater() == WATER_PUMP) {
            hsPumps.add(itemKey(gear));
        }
        if (imi.getDcSecurity() == SECURITY_CAMERA) {
            alCameras.add(gear);
        }
        setRunning(gear, true);
    }

    /**
     * @return the cooling of so much air and water cooling, after a heat
     *         wave's cut of the air cooling
     */
    private static int getCooling(int iAir, int iWater) {
        return iAir * airPct / 100 + iWater;
    }

    /**
     * Backup power: the switch and the cooling a rack needs, from the gear
     * still waiting (alSpare, in install order), with a pump for a
     * water-cooled unit when none of the pumps whose lines reach it runs.
     * They start only when the power left covers them and the rack.
     *
     * @return -1 when the rack can run, or why it cannot (REASON_*)
     */
    private static int startForRack(int iNeed, int iPorts, int iHeat, ArrayList<Item> alSpare, HashMap<Integer, ArrayList<Item>> hmFeeders, HashSet<Integer> hsPumps) {
        ArrayList<Item> alStart = new ArrayList<Item>();
        int iPower = powerUsed + iNeed;
        int iFree = ports - portsUsed;
        for (int i = 0; i < alSpare.size() && iFree < iPorts; i++) {
            ItemManagerItem imi = ItemManager.getItem(alSpare.get(i).getIniHeader());
            if (imi.getDcPorts() > 0) {
                alStart.add(alSpare.get(i));
                iPower -= imi.getDcPower();
                iFree += imi.getDcPorts();
            }
        }
        if (iFree < iPorts) {
            return REASON_NETWORK;
        }
        int iAir = airCooling;
        int iWater = waterCooling;
        HashSet<Integer> hsPumpsOn = new HashSet<Integer>(hsPumps);
        for (int i = 0; i < alSpare.size() && heat + iHeat > getCooling(iAir, iWater); i++) {
            Item unit = alSpare.get(i);
            ItemManagerItem imi = ItemManager.getItem(unit.getIniHeader());
            if (imi.getDcCooling() <= 0) {
                continue;
            }
            if (imi.getDcWater() == WATER_UNIT) {
                ArrayList<Item> alFeeders = hmFeeders.get(Integer.valueOf(unit.getID()));
                if (alFeeders == null) {
                    // No line to a pump at water
                    continue;
                }
                boolean bFed = false;
                for (Item pump : alFeeders) {
                    bFed = bFed || hsPumpsOn.contains(itemKey(pump));
                }
                if (!bFed) {
                    Item pump = alFeeders.get(0);
                    alStart.add(pump);
                    hsPumpsOn.add(itemKey(pump));
                    iPower -= ItemManager.getItem(pump.getIniHeader()).getDcPower();
                }
                iWater += imi.getDcCooling();
            } else {
                iAir += imi.getDcCooling();
            }
            alStart.add(unit);
            iPower -= imi.getDcPower();
        }
        if (heat + iHeat > getCooling(iAir, iWater)) {
            return REASON_COOLING;
        }
        if (iPower > powerSupply) {
            return REASON_POWER;
        }
        for (Item gear : alStart) {
            startGear(gear, hsPumps);
        }
        alSpare.removeAll(alStart);
        return -1;
    }

    /**
     * Dawn: the bills are paid, the customers pay for the hours their racks
     * ran, three dawns in a row below zero coins lose the site, a day every
     * rack ran raises the reputation, empty racks may sign customers, the log
     * says when a failing or dead drive has no new drive to go to, the
     * running racks roll for service events, the day may get a grid outage,
     * a heat wave or a protest, and the miserable are warned or quit.
     */
    public static void newDay() {
        if (Game.isHeadless() && (racks > 0 || powerSupply > 0)) {
            // The headless runs print the hall once a day, for balancing
            String sProblems = getStatusProblems();
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " " + getStatusLine() + "   " + getStatusBusiness() + (sProblems == null ? "" : "   " + sProblems) + " | coins " + Game.getWorld().getCoins() + " | " + describeRacks()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }
        // Customers pay for the hours their racks ran, the day's average of
        // each turn's pay: paying for the racks running at dawn made an
        // outage over the dawn cost a whole day
        int iPaid = turnsToday > 0 ? (int) Math.round((double) payToday / turnsToday) : payPerDay;
        uptimePct = payFullToday > 0 ? (int) Math.min(100, payToday * 100 / payFullToday) : 100;
        payToday = 0;
        payFullToday = 0;
        int iBills = payBills();
        Buyout.noteDay(iPaid, iBills, uptimePct);
        if (iPaid > 0) {
            Game.getWorld().setCoins(Game.getWorld().getCoins() + iPaid);
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.paid"), iPaid), ColorGL.GREEN); //$NON-NLS-1$
        }
        if (Game.isHeadless() && (racks > 0 || powerSupply > 0)) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " paid " + iPaid + ", bills " + iBills); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        checkBroke();
        updateReputation();
        signCustomers();
        // Clone and Rescue start by picking up a new drive, and without one
        // their orders wait with nothing to say why
        if (racksFailing + racksDown[REASON_DRIVE] > 0 && Item.getNumItems(UtilsIniHeaders.getIntIniHeader("dcdrive"), false, World.MAP_DEPTH) == 0) { //$NON-NLS-1$
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.noDrives"), ColorGL.ORANGE); //$NON-NLS-1$
        }
        // Repairing a rack starts by picking up a box of spare parts, the same way
        if (racksDown[REASON_DRIVE + DOWN_WRECKED - 1] > 0 && Item.getNumItems(UtilsIniHeaders.getIntIniHeader("dcparts"), false, World.MAP_DEPTH) == 0) { //$NON-NLS-1$
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.noParts"), ColorGL.ORANGE); //$NON-NLS-1$
        }
        rollServiceEvents();
        rollOutage();
        rollHeatWave();
        Network.newDay(isTutorial() || Buyout.isSold());
        Protests.newDay(isTutorial());
        Bear.newDay(isTutorial());
        Thieves.newDay(isTutorial());
        Housing.newDay(isTutorial(), Morale.newDay(isTutorial()));
        Weather.newDay(isTutorial());
        MoveOuts.newDay(alRunningRackIDs, hsEmptyRacks, isTutorial() || Buyout.isSold());
        Buyout.newDay(customers, reputation, isTutorial());
    }

    /**
     * @return true when the item with this ID is an installed rack that is not
     *         running, or wrecked gear waiting for a Repair: the map draws the
     *         red cross on it, though it stays operative
     */
    public static boolean isDown(int iItemID) {
        Integer id = Integer.valueOf(iItemID);
        return hsDownRacks.contains(id) || hsWreckedGear.contains(id);
    }

    /**
     * @return true for installed gear that waits for one of its own jobs (the
     *         Parted Magic tools, Repair, Erase): a rack under the red cross
     *         for a dead drive, a service call or damage, wrecked gear, a
     *         flagged drive, a rack to wipe, a body to bury.  Its right-click
     *         menu lists those jobs first (Item.fillMenu)
     */
    public static boolean needsFix(Item item) {
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        if (imi == null || !imi.hasActions() || !isInstalled(item)) {
            return false;
        }
        // The bear's victim too: Bury, before a ghost walks (Patrick,
        // 2026-10-31: "how do you bury a dead person?")
        return imi.isDcDown() || imi.isDcFlagged() || hsWreckedGear.contains(Integer.valueOf(item.getID())) || MoveOuts.VACATED_RACK.equals(item.getIniHeader()) || Bear.BODY.equals(item.getIniHeader()) || imi.getDcNet() == Network.NET_DNS_DOWN;
    }

    /**
     * @return a line for the map tooltip on gear that needs a hand (Patrick,
     *         2026-10-31: "it's not obvious what to do with the server with
     *         an x on it"): the jobs a right-click offers for it, or why a
     *         rack is down for want of power, cooling or a port, or for a
     *         rack with no customer the chance one signs at dawn; null when
     *         nothing needs doing
     */
    public static String getFixHint(Item item) {
        if (needsFix(item)) {
            ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
            ArrayList<String> alJobs = new ArrayList<String>();
            for (String sAction : imi.getActions()) {
                ActionManagerItem ami = ActionManager.getItem(sAction);
                if (ami != null && ami.getName() != null && !alJobs.contains(ami.getName())) {
                    alJobs.add(ami.getName());
                }
            }
            if (!alJobs.isEmpty()) {
                return MessageFormat.format(Messages.getString("DataCenter.hintFix"), String.join(" " + Messages.getString("DataCenter.hintOr") + " ", alJobs)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
        }
        Integer iReason = hmDownReason.get(Integer.valueOf(item.getID()));
        if (iReason != null && iReason.intValue() == REASON_OFFLINE) {
            return Network.getRackHint();
        }
        if (iReason != null && iReason.intValue() < HINT_KEYS.length) {
            return Messages.getString(HINT_KEYS[iReason.intValue()]);
        }
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        // A fiber line that is cut: who fixes it
        if (imi != null && Network.getBoxHint(imi.getDcNet()) != null) {
            return Network.getBoxHint(imi.getDcNet());
        }
        // A food machine with nowhere to put what it makes
        if (item.isBoxedIn()) {
            return Messages.getString("DataCenter.hintNoRoom"); //$NON-NLS-1$
        }
        // A running rack waiting for a customer: what brings one
        if (imi != null && imi.getDcIncome() > 0 && isInstalled(item) && hsEmptyRacks.contains(itemKey(item))) {
            return MessageFormat.format(Messages.getString("DataCenter.hintEmpty"), getSignPCT()); //$NON-NLS-1$
        }
        // A guard post with no guard: past the third, or no donut shop yet
        if (imi != null && imi.getDcSecurity() == SECURITY_POST && Protests.isUnstaffed(item)) {
            return Messages.getString("DataCenter.hintNoGuard"); //$NON-NLS-1$
        }
        if (imi != null && imi.getDcSecurity() == SECURITY_POST && Protests.isWaitingForDonuts(item)) {
            return Messages.getString("DataCenter.hintNoDonuts"); //$NON-NLS-1$
        }
        // Gear lying loose (dropped beside the spot it was ordered for): how
        // to put it in place (the build menu's order moves a loose one of its
        // kind first, Task's TASK_MOVE_AND_LOCK)
        if (imi != null && !item.isLocked() && isGear(imi)) {
            return Messages.getString("DataCenter.hintLooseGear"); //$NON-NLS-1$
        }
        // A box on the ground: where its install job is
        return Boxes.getInstallHint(item);
    }

    /**
     * Why the build menu refuses another of this item anywhere: a fourth
     * guard post (Protests.MAX_GUARDS), counting the posts installed and the
     * install orders waiting.  One a technician has already started is not
     * counted, so a post can still end up past the third, and it hires no one.
     *
     * @return a messages key, or null
     */
    public static String getPlaceLimitReason(ItemManagerItem imi) {
        if (imi == null || imi.getDcSecurity() != SECURITY_POST || Game.getWorld() == null) {
            return null;
        }
        int iPosts = Protests.getPostsInstalled();
        xaos.tasks.TaskManager tm = Game.getWorld().getTaskManager();
        for (ArrayList<xaos.actions.Action> al : java.util.List.of(tm.getCustomActions(), tm.getCustomActionsTemp(), tm.getCustomActionsWait())) {
            for (xaos.actions.Action action : al) {
                ActionManagerItem ami = ActionManager.getItem(action.getId());
                if (ami != null && imi.getIniHeader().equals(ami.getGeneratedItem())) {
                    iPosts++;
                }
            }
        }
        return iPosts >= Protests.MAX_GUARDS ? "Item.placeGuardPosts" : null; //$NON-NLS-1$
    }

    /**
     * @return the line under the top bar (power, heat, ports, racks
     *         running), or null before anything is installed
     */
    public static String getStatusLine() {
        if (powerSupply == 0 && racks == 0) {
            return null;
        }
        String sLine = MessageFormat.format(Messages.getString("DataCenter.status"), powerUsed, powerSupply, heat, coolingCapacity, portsUsed, ports, racksOnline, racks); //$NON-NLS-1$
        if (batteryCapacity > 0) {
            sLine += "   " + MessageFormat.format(Messages.getString("DataCenter.statusBatteries"), (int) ((long) batteryCharge * 100 / batteryCapacity)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (dieselPower > 0) {
            sLine += "   " + MessageFormat.format(Messages.getString("DataCenter.statusDiesel"), dieselPower); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sLine;
    }

    /**
     * @return the second status line (customers, reputation, the day's pay
     *         and bills), or null before anything is installed.  On the
     *         first line they took it past the room a 1024 wide window has.
     */
    public static String getStatusBusiness() {
        if (powerSupply == 0 && racks == 0) {
            return null;
        }
        String sBuyout = Buyout.getStatusBusiness();
        return MessageFormat.format(Messages.getString("DataCenter.statusBusiness"), customers, racks, reputation, payPerDay, getBillsPerDay()) //$NON-NLS-1$
                + "   " + MessageFormat.format(Messages.getString("DataCenter.statusMorale"), Morale.getMorale()) //$NON-NLS-1$ //$NON-NLS-2$
                + (sBuyout == null ? "" : "   " + sBuyout); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return the third status line, what needs doing (out of money, a
     *         customer about to leave, failing and dead drives, service
     *         events), or null when nothing does.  With all of them on one
     *         line it ran past a 1024 wide window.
     */
    public static String getStatusProblems() {
        ArrayList<String> alParts = new ArrayList<String>();
        // Sold: the wipe is all that matters now, and no customer is left to
        // call about DNS
        String sSale = Buyout.getStatusProblems();
        if (sSale != null) {
            return sSale;
        }
        String sToWipe = MoveOuts.getStatus();
        if (sToWipe != null) {
            alParts.add(sToWipe);
        }
        if (reputation < REPUTATION_LOW && !siteLost && !isTutorial()) {
            alParts.add(Messages.getString("DataCenter.statusReputationLow")); //$NON-NLS-1$
        }
        int iQuitting = Morale.getQuitting();
        if (iQuitting > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusQuitting"), iQuitting)); //$NON-NLS-1$
        }
        int iUnhappy = Morale.getUnhappy();
        if (iUnhappy > iQuitting) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusUnhappy"), iUnhappy)); //$NON-NLS-1$
        }
        String sHousing = Housing.getStatus(isTutorial());
        if (sHousing != null) {
            alParts.add(sHousing);
        }
        String sLeisure = Leisure.getStatus(isTutorial());
        if (sLeisure != null) {
            alParts.add(sLeisure);
        }
        if (looseGear > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusLooseGear"), looseGear)); //$NON-NLS-1$
        }
        if (foodMachines == 0 && racks > 0 && !isTutorial()) {
            alParts.add(Messages.getString("DataCenter.statusNoFood")); //$NON-NLS-1$
        }
        // What the hall lacks, in words: on 2026-11-04 a stolen switch stopped
        // every rack and only "Ports 0/0" said why
        if (racksDown[REASON_NETWORK] > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusNoPort"), racksDown[REASON_NETWORK])); //$NON-NLS-1$
        }
        if (racksDown[REASON_COOLING] > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusNoCooling"), racksDown[REASON_COOLING])); //$NON-NLS-1$
        }
        if (racksDown[REASON_POWER] > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusNoPower"), racksDown[REASON_POWER])); //$NON-NLS-1$
        }
        int iHungry = Morale.getHungry();
        if (iHungry >= Math.max(HUNGRY_SHOWN_MIN, World.getCitizenIDs().size() / 3)) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusHungry"), iHungry)); //$NON-NLS-1$
        }
        String sDonuts = Protests.getDonutStatus();
        if (sDonuts != null) {
            alParts.add(sDonuts);
        }
        String sProtest = Protests.getStatus();
        if (sProtest != null) {
            alParts.add(sProtest);
        }
        String sBear = Bear.getStatus();
        if (sBear != null) {
            alParts.add(sBear);
        }
        String sThieves = Thieves.getStatus();
        if (sThieves != null) {
            alParts.add(sThieves);
        }
        String sHoles = Thieves.getHolesStatus();
        if (sHoles != null) {
            alParts.add(sHoles);
        }
        String sRoof = Roofs.getStatus();
        if (sRoof != null) {
            alParts.add(sRoof);
        }
        String sUnreachable = Boxes.getUnreachableStatus();
        if (sUnreachable != null) {
            alParts.add(sUnreachable);
        }
        String sBoxes = Boxes.getStatus();
        if (sBoxes != null) {
            alParts.add(sBoxes);
        }
        String sDns = Network.getDnsStatus();
        if (sDns != null) {
            alParts.add(sDns);
        }
        String sFiber = Network.getFiberStatus();
        if (sFiber != null) {
            alParts.add(sFiber);
        }
        if (outageLeft > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusOutage"), (outageLeft + World.TIME_MODIFIER_HOUR - 1) / World.TIME_MODIFIER_HOUR)); //$NON-NLS-1$
        }
        int iHeatLeft = getHeatWaveTurnsLeft();
        if (iHeatLeft > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusHeatWave"), HEAT_WAVE_AIR_PCT, (iHeatLeft + World.TIME_MODIFIER_HOUR - 1) / World.TIME_MODIFIER_HOUR)); //$NON-NLS-1$
        }
        String sRain = Weather.getStatus();
        if (sRain != null) {
            alParts.add(sRain);
        }
        if (pumpsDry > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusPumpDry"), pumpsDry)); //$NON-NLS-1$
        }
        if (unitsDry > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusUnitDry"), unitsDry)); //$NON-NLS-1$
        }
        if (brokeDawns > 0 && !siteLost) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusBroke"), BROKE_DAWNS - brokeDawns)); //$NON-NLS-1$
        }
        int iLeaving = getTurnsToNextLeaving();
        if (iLeaving > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.statusLeaving"), (iLeaving + World.TIME_MODIFIER_HOUR - 1) / World.TIME_MODIFIER_HOUR)); //$NON-NLS-1$
        }
        if (racksFailing > 0) {
            alParts.add(MessageFormat.format(Messages.getString("DataCenter.failing"), racksFailing).trim()); //$NON-NLS-1$
        }
        for (int i = REASON_DRIVE; i < racksDown.length; i++) {
            // Wrecked counts every wrecked piece, the grid feed and switches too
            int iCount = racksDown[i] + (i == REASON_DRIVE + DOWN_WRECKED - 1 ? wreckedOther : 0);
            if (iCount > 0) {
                alParts.add(MessageFormat.format(Messages.getString(STATUS_KEYS[i - REASON_DRIVE]), iCount).trim());
            }
        }
        if (alParts.isEmpty()) {
            return null;
        }
        return String.join("   ", alParts); //$NON-NLS-1$
    }

    /**
     * @return true once the site is lost (the panel that says so stays up)
     */
    public static boolean isSiteLost() {
        return siteLost;
    }

    /**
     * @return a truck's markup moved by the reputation, 10% lower at 100 and
     *         10% higher at 0 (CaravanManagerItem, once a visit: the trade
     *         window finds the truck's items again by their price)
     */
    public static int getTruckPricePCT(int iPricePCT) {
        return (iPricePCT * (1000 + (REPUTATION_START - reputation) * 2) + 500) / 1000;
    }

    /**
     * In updateHall, before the racks are sorted: a rack in a cell that held
     * none at the last look is new, and starts with no customer unless one
     * is waiting for it (the starting kit's boxed racks).  It signs one the
     * way any empty rack does (signCustomers), so the reputation decides how
     * fast a growing hall fills (Patrick, 2026-11-01, after a played site
     * went from 4 racks to 37 with every new rack paying from its first
     * day).  A rack that a job, wear, a service event or a wreck swapped
     * stands in a known cell and keeps its customer.  The first look at a
     * game just made, or loaded from before this rule, takes its racks as
     * they are.
     *
     * @param alRacks the installed racks, in install order
     */
    private static void noteNewRacks(ArrayList<Item> alRacks) {
        HashSet<Integer> hsKeys = new HashSet<Integer>();
        for (Item rack : alRacks) {
            Integer iKey = itemKey(rack);
            hsKeys.add(iKey);
            if (knownRacks && !hsKnownRacks.contains(iKey)) {
                if (waitingCustomers > 0) {
                    waitingCustomers--;
                } else {
                    hsEmptyRacks.add(iKey);
                }
            }
        }
        // A rack waiting to be wiped is not counted, but its cell stays
        // known: wiped, it is empty (MoveOuts) and must not take a waiting
        // customer as a new rack would
        hsKeys.addAll(MoveOuts.getCells());
        hsKnownRacks.clear();
        hsKnownRacks.addAll(hsKeys);
        knownRacks = true;
    }

    /**
     * Every turn, after updateHall: counts each down rack's turns down, and
     * one down a full day loses its customer (not in a tutorial).  Racks no
     * longer installed are forgotten.
     */
    private static void watchCustomers() {
        HashSet<Integer> hsKeys = new HashSet<Integer>();
        for (Item rack : alHallRacks) {
            Integer iKey = itemKey(rack);
            hsKeys.add(iKey);
            if (!hsDownRacks.contains(Integer.valueOf(rack.getID()))) {
                hmDownTurns.remove(iKey);
                continue;
            }
            // Only a rack with a customer spoils the day: an empty one waiting
            // on power or cooling let nobody down
            if (!hsEmptyRacks.contains(iKey)) {
                allRanToday = false;
            }
            Integer iTurns = hmDownTurns.get(iKey);
            int iDown = iTurns == null ? 1 : iTurns.intValue() + 1;
            hmDownTurns.put(iKey, Integer.valueOf(iDown));
            if (iDown >= World.TIME_MODIFIER_DAY && !hsEmptyRacks.contains(iKey) && !siteLost && !isTutorial()) {
                loseCustomer(rack, iKey);
            }
        }
        hsEmptyRacks.retainAll(hsKeys);
        hmDownTurns.keySet().retainAll(hsKeys);
    }

    /**
     * A wreck made the news (Protests): the reputation drops, and at 0 the
     * site is lost
     *
     * @return the reputation after it
     */
    static int newsHit() {
        return newsHit(REPUTATION_NEWS);
    }

    /**
     * The protest itself is on the news (Protests): a smaller drop.  Also a
     * protester walking off with an unerased drive (Protests), and an old
     * customer whose erase certificate came late (MoveOuts).
     *
     * @return the reputation after it
     */
    static int newsHit(int iLoss) {
        if (siteLost || isTutorial()) {
            return reputation;
        }
        reputation = Math.min(REPUTATION_MAX, Math.max(0, reputation - iLoss));
        return reputation;
    }

    /**
     * A rack carried off by copper thieves (Thieves): its customer goes too,
     * and the cell stays without one for a rack installed there later
     */
    static void rackStolen(Item rack) {
        Integer iKey = itemKey(rack);
        if (!hsEmptyRacks.contains(iKey)) {
            loseCustomer(rack, iKey);
        }
        hmDownTurns.remove(iKey);
    }

    private static void loseCustomer(Item rack, Integer iKey) {
        hsEmptyRacks.add(iKey);
        customers--;
        reputation = Math.max(0, reputation - REPUTATION_CUSTOMER_LOST);
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " customer lost at " + rack.getX() + "," + rack.getY() + "," + rack.getZ() + ", reputation " + reputation); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.customerLost"), reputation), ColorGL.RED, new Point3DShort(rack.getX(), rack.getY(), rack.getZ())); //$NON-NLS-1$
    }

    /**
     * @return the turns until the next customer leaves, or 0 when no rack
     *         that has a customer is down (none leave in a tutorial)
     */
    private static int getTurnsToNextLeaving() {
        int iMost = 0;
        for (Map.Entry<Integer, Integer> entry : hmDownTurns.entrySet()) {
            if (entry.getValue().intValue() > iMost && !hsEmptyRacks.contains(entry.getKey())) {
                iMost = entry.getValue().intValue();
            }
        }
        if (iMost == 0 || siteLost || isTutorial()) {
            return 0;
        }
        return Math.max(1, World.TIME_MODIFIER_DAY - iMost);
    }

    /**
     * Dawn: a day the racks with customers earned most of their pay raises the
     * reputation, and a new day starts
     */
    private static void updateReputation() {
        if (racks > 0 && !siteLost) {
            int iGain = uptimePct >= UPTIME_GOOD_PCT ? REPUTATION_GOOD_DAY : uptimePct >= UPTIME_FAIR_PCT ? REPUTATION_FAIR_DAY : 0;
            reputation = Math.min(REPUTATION_MAX, reputation + iGain);
        }
        allRanToday = true;
    }

    /**
     * Dawn: each running rack with no customer signs one with a chance of
     * the reputation and SIGN_BASE in SIGN_DIE (35% at 50), in install order
     */
    private static void signCustomers() {
        if (hsEmptyRacks.isEmpty() || siteLost || Buyout.isSold()) {
            return;
        }
        for (Integer iID : alRunningRackIDs) {
            Item rack = Item.getItemByID(iID.intValue());
            if (rack == null || !isInstalled(rack)) {
                continue;
            }
            Integer iKey = itemKey(rack);
            if (hsEmptyRacks.contains(iKey) && Utils.getRandomBetween(1, SIGN_DIE) <= reputation + SIGN_BASE) {
                hsEmptyRacks.remove(iKey);
                if (Game.isHeadless()) {
                    System.out.println("[DataCenter] " + Game.getWorld().getDate() + " customer signed at " + rack.getX() + "," + rack.getY() + "," + rack.getZ()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.customerSigned"), ColorGL.GREEN, new Point3DShort(rack.getX(), rack.getY(), rack.getZ())); //$NON-NLS-1$
            }
        }
    }

    /**
     * @return the chance in percent that a running empty rack signs a
     *         customer at the next dawn (signCustomers)
     */
    private static int getSignPCT() {
        return Math.min(100, (reputation + SIGN_BASE) * 100 / SIGN_DIE);
    }

    /**
     * @return the key of the cell an item stands in
     */
    private static Integer itemKey(Item item) {
        return Integer.valueOf((item.getZ() * World.MAP_HEIGHT + item.getY()) * World.MAP_WIDTH + item.getX());
    }

    static int cellKey(Item item) {
        return itemKey(item).intValue();
    }

    /**
     * @return x, y and z of a cell key (itemKey)
     */
    static int[] cellOf(int iKey) {
        int iPlane = World.MAP_WIDTH * World.MAP_HEIGHT;
        return new int[] {iKey % World.MAP_WIDTH, (iKey / World.MAP_WIDTH) % World.MAP_HEIGHT, iKey / iPlane};
    }

    /**
     * @return true while the sun is up: the day and the changes into and out
     *         of it (events.xml), about 72% of the day
     */
    static boolean isDaylight() {
        for (EventData event : Game.getWorld().getEvents()) {
            String sID = event.getEventID();
            if ("day".equals(sID) || sID.startsWith("preday") || sID.startsWith("preevening")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return true;
            }
        }
        return false;
    }

    /**
     * @return a battery's full charge in kW-turns: its kW for 4 hours
     */
    private static int getBatteryCapacity(ItemManagerItem imi) {
        return imi.getDcPower() * BATTERY_HOURS * World.TIME_MODIFIER_HOUR;
    }

    private static int getBatteryCharge(Item battery, int iCapacity) {
        Integer iCharge = hmBatteryCharge.get(itemKey(battery));
        return iCharge == null ? iCapacity : Math.min(iCapacity, iCharge.intValue());
    }

    /**
     * Every turn, before updateHall: a coming outage starts at its hour, and
     * one that is on counts down
     */
    private static void updateOutage() {
        if (outageLeft > 0) {
            outageLeft--;
            if (outageLeft == 0) {
                if (Game.isHeadless()) {
                    System.out.println("[DataCenter] " + Game.getWorld().getDate() + " grid back"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.gridBack"), ColorGL.GREEN); //$NON-NLS-1$
            }
        } else if (outageStart >= 0 && Game.getWorld().getTurn() >= outageStart) {
            outageLeft = outageLength;
            outageStart = -1;
            if (Game.isHeadless()) {
                System.out.println("[DataCenter] " + Game.getWorld().getDate() + " grid down for " + outageLength / World.TIME_MODIFIER_HOUR + " h"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.outage"), ColorGL.RED); //$NON-NLS-1$
        }
    }

    /**
     * Dawn, from day 5: a 1 in 4 chance of a grid outage that day, from a
     * random hour for 2 to 8 hours (not in a tutorial)
     */
    private static void rollOutage() {
        if (siteLost || !hasGrid || outageLeft > 0 || outageStart >= 0 || isTutorial() || Game.getWorld().getDate().getDaysSinceStart() < OUTAGE_FROM_DAY) {
            return;
        }
        if (Utils.getRandomBetween(1, 100) <= OUTAGE_PCT) {
            outageStart = Utils.getRandomBetween(0, 23) * World.TIME_MODIFIER_HOUR;
            outageLength = Utils.getRandomBetween(2, 8) * World.TIME_MODIFIER_HOUR;
        }
    }

    /**
     * @return the turns left of the heat wave, or 0 when none is on
     */
    private static int getHeatWaveTurnsLeft() {
        if (Game.getWorld() == null) {
            return 0;
        }
        for (EventData event : Game.getWorld().getEvents()) {
            if (HEAT_WAVE_EVENT.equals(event.getEventID())) {
                return Math.max(1, event.getTurns());
            }
        }
        return 0;
    }

    private static boolean isHeatWave() {
        return getHeatWaveTurnsLeft() > 0;
    }

    /**
     * Every turn: the log says when a heat wave starts and ends
     */
    private static void reportHeatWave() {
        boolean bHeat = isHeatWave();
        if (bHeat == heatWave) {
            return;
        }
        heatWave = bHeat;
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + (bHeat ? " heat wave for " + getHeatWaveTurnsLeft() / World.TIME_MODIFIER_HOUR + " h" : " heat wave over")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString(bHeat ? "DataCenter.heatWave" : "DataCenter.heatWaveOver"), bHeat ? ColorGL.ORANGE : ColorGL.GREEN); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Dawn, from day 4: about one day in seven a heat wave starts, Towns' "hot"
     * event set to last 1 to 2 days (not in a tutorial).  A cold spell keeps
     * it away (events.xml).
     */
    private static void rollHeatWave() {
        if (siteLost || isHeatWave() || isTutorial() || Game.getWorld().getDate().getDaysSinceStart() < HEAT_WAVE_FROM_DAY) {
            return;
        }
        if (Utils.getRandomBetween(1, 100) > HEAT_WAVE_PCT) {
            return;
        }
        EventManagerItem emi = EventManager.getItem(HEAT_WAVE_EVENT);
        if (emi == null || !Game.getWorld().addEvent(emi)) {
            return;
        }
        int iTurns = Utils.getRandomBetween(24, 48) * World.TIME_MODIFIER_HOUR;
        for (EventData event : Game.getWorld().getEvents()) {
            if (HEAT_WAVE_EVENT.equals(event.getEventID())) {
                event.setTurns(iTurns);
            }
        }
        Game.getWorld().checkGlobalEvents();
    }

    /**
     * @return true when a cell beside the pump, on its level or the one
     *         below (a river under its bank), holds water
     */
    private static boolean isNextToWater(Item pump) {
        int x = pump.getX();
        int y = pump.getY();
        for (int z = pump.getZ(); z <= pump.getZ() + 1 && z < World.MAP_DEPTH; z++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= World.MAP_WIDTH || ny >= World.MAP_HEIGHT) {
                        continue;
                    }
                    Terrain terrain = World.getCell(nx, ny, z).getTerrain();
                    if (terrain.getFluidType() == Terrain.FLUIDS_WATER && terrain.getFluidCount() > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @return the cells water reaches: the running pumps and the pipes joined
     *         to them, cell to cell (neighborKeys)
     */
    private static HashSet<Integer> reachWater(HashSet<Integer> hsPumps, HashSet<Integer> hsPipes) {
        HashSet<Integer> hsReached = new HashSet<Integer>(hsPumps);
        ArrayList<Integer> alQueue = new ArrayList<Integer>(hsPumps);
        for (int i = 0; i < alQueue.size(); i++) {
            for (Integer iNext : neighborKeys(alQueue.get(i).intValue())) {
                if (hsPipes.contains(iNext) && hsReached.add(iNext)) {
                    alQueue.add(iNext);
                }
            }
        }
        return hsReached;
    }

    private static boolean touchesWater(Item unit, HashSet<Integer> hsWater) {
        for (Integer iNext : neighborKeys(itemKey(unit).intValue())) {
            if (hsWater.contains(iNext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the keys (itemKey) of the cells N, S, E and W of a cell, on its
     *         level and the ones above and below, inside the map: a pipe line
     *         follows the ground, and surface water lies a level or two below
     *         the sites
     */
    private static ArrayList<Integer> neighborKeys(int iKey) {
        int iPlane = World.MAP_WIDTH * World.MAP_HEIGHT;
        int x = iKey % World.MAP_WIDTH;
        int y = (iKey / World.MAP_WIDTH) % World.MAP_HEIGHT;
        int z = iKey / iPlane;
        ArrayList<Integer> al = new ArrayList<Integer>(12);
        for (int dz = -1; dz <= 1; dz++) {
            if (z + dz < 0 || z + dz >= World.MAP_DEPTH) {
                continue;
            }
            int iBase = iKey + dz * iPlane;
            if (y > 0) {
                al.add(Integer.valueOf(iBase - World.MAP_WIDTH));
            }
            if (y < World.MAP_HEIGHT - 1) {
                al.add(Integer.valueOf(iBase + World.MAP_WIDTH));
            }
            if (x < World.MAP_WIDTH - 1) {
                al.add(Integer.valueOf(iBase + 1));
            }
            if (x > 0) {
                al.add(Integer.valueOf(iBase - 1));
            }
        }
        return al;
    }

    /**
     * @return the bills a day at the current draw from the grid and the
     *         generators, the generators' upkeep, the technicians and guards
     */
    static int getBillsPerDay() {
        return gridPower * POWER_PRICE + dieselPower * DIESEL_PRICE + generatorUpkeep + World.getCitizenIDs().size() * TECHNICIAN_PAY + Protests.getGuards() * Protests.GUARD_PAY;
    }

    /**
     * Dawn's bills: grid power and diesel at the day's average draw, the
     * generators' upkeep, and the technicians' and guards' pay
     *
     * @return what was paid
     */
    private static int payBills() {
        int iGrid = (turnsToday > 0 ? (int) Math.round((double) powerUsedToday / turnsToday) : gridPower) * POWER_PRICE;
        int iDiesel = (turnsToday > 0 ? (int) Math.round((double) dieselUsedToday / turnsToday) : dieselPower) * DIESEL_PRICE + generatorUpkeep;
        int iPay = World.getCitizenIDs().size() * TECHNICIAN_PAY + Protests.getGuards() * Protests.GUARD_PAY;
        int iBills = iGrid + iDiesel + iPay;
        powerUsedToday = 0;
        dieselUsedToday = 0;
        turnsToday = 0;
        if (iBills > 0) {
            Game.getWorld().setCoins(Game.getWorld().getCoins() - iBills);
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.bills"), iBills, iGrid, iDiesel, iPay), ColorGL.ORANGE); //$NON-NLS-1$
        }
        return iBills;
    }

    /**
     * After the bills and the pay: a dawn below zero coins counts toward
     * losing the site, a dawn at zero or above starts the count again
     */
    private static void checkBroke() {
        // A sold site's debts are the buyer's now
        if (siteLost || isTutorial() || Buyout.isSold()) {
            return;
        }
        if (Game.getWorld().getCoins() >= 0) {
            brokeDawns = 0;
            return;
        }
        brokeDawns++;
        if (brokeDawns >= BROKE_DAWNS) {
            loseSite("DataCenter.lostBroke"); //$NON-NLS-1$
            return;
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.broke"), BROKE_DAWNS - brokeDawns), ColorGL.RED); //$NON-NLS-1$
    }

    /**
     * The site is lost: the game stops on a panel that says why and how long
     * the site lasted, and goes back to the main menu (CommandPanel)
     */
    private static void loseSite(String sReasonKey) {
        siteLost = true;
        int iDays = Game.getWorld().getDate().getDaysSinceStart();
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " site lost: " + Messages.getString(sReasonKey)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString(sReasonKey), ColorGL.RED);
        CommandPanel.showSiteLost(Messages.getString(sReasonKey), iDays);
    }

    /**
     * The sale has closed (Buyout): the game is over as it is for a lost site,
     * with the same panel held up and nothing saved after it
     */
    static void endSite() {
        siteLost = true;
    }

    /**
     * The sale (Buyout): every installed rack, whatever state it is in, is now a
     * rack to wipe, and nobody is a customer any more.  A wiped rack comes back
     * empty (noteNewRacks).
     *
     * @return the racks' cell keys
     */
    static ArrayList<Integer> vacateRacks() {
        ArrayList<Item> alRacks = new ArrayList<Item>();
        for (Item item : World.getItems().values()) {
            ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
            if (imi != null && isInstalled(item) && (imi.getDcIncome() > 0 || MoveOuts.VACATED_RACK.equals(item.getIniHeader()))) {
                alRacks.add(item);
            }
        }
        ArrayList<Integer> alKeys = new ArrayList<Integer>();
        for (Item rack : alRacks) {
            alKeys.add(Integer.valueOf(cellKey(rack)));
            if (!MoveOuts.VACATED_RACK.equals(rack.getIniHeader())) {
                replaceRack(rack, MoveOuts.VACATED_RACK);
            }
        }
        waitingCustomers = 0;
        hmDownTurns.clear();
        return alKeys;
    }

    /**
     * @return true in a tutorial mission, where the site cannot be lost
     */
    static boolean isTutorial() {
        MissionData mission = Game.getCurrentMissionData();
        return mission != null && mission.getTutorialFlows() != null && mission.getTutorialFlows().size() > 1;
    }

    /**
     * Puts the starting kit on free ground around the arrival point: the
     * nearest free cells first, never the arrival cell itself.
     *
     * A machine that puts things out (the vending and coffee machines) puts
     * them on the eight cells around it, and makes nothing while none of
     * those is free.  So the machines go first, each where all eight cells
     * are free, and the rest of the kit keeps off them.
     */
    public static void placeStartingKit(int x, int y, int z) {
        clearLot(x, y, z);
        HashSet<Integer> hsKeepClear = new HashSet<Integer>();
        ArrayList<String> alRest = new ArrayList<String>();
        for (String sIniHeader : STARTING_KIT) {
            ItemManagerItem imi = ItemManager.getItem(sIniHeader);
            if (imi == null || imi.getSpawn() == null || !placeWithRoom(imi, x, y, z, hsKeepClear)) {
                alRest.add(sIniHeader);
            }
        }
        int iNext = 0;
        for (int iRadius = 2; iRadius <= 12 && iNext < alRest.size(); iRadius++) {
            for (int dy = -iRadius; dy <= iRadius && iNext < alRest.size(); dy++) {
                for (int dx = -iRadius; dx <= iRadius && iNext < alRest.size(); dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) == iRadius && !hsKeepClear.contains(cellKey(x + dx, y + dy)) && placeItem(alRest.get(iNext), x + dx, y + dy, z)) {
                        iNext++;
                    }
                }
            }
        }
        if (iNext < alRest.size()) {
            Log.log(Log.LEVEL_ERROR, "Data center starting kit: no room for " + (alRest.size() - iNext) + " pieces", "DataCenter"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * A cleared lot for the hall (2026-10-31): the trees the map was made
     * with stood among the starting kit and hid it.  No tree, stump or young
     * tree within CLEARED_RADIUS of the arrival point; the woods around the
     * lot give the wood.  The world's items are not inited yet (Game.startGame
     * inits them), so emptying the cell leaves nothing behind.
     */
    private static void clearLot(int x, int y, int z) {
        for (int dx = -CLEARED_RADIUS; dx <= CLEARED_RADIUS; dx++) {
            for (int dy = -CLEARED_RADIUS; dy <= CLEARED_RADIUS; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int cx = x + dx, cy = y + dy, cz = z + dz;
                    if (cx < 0 || cy < 0 || cz < 0 || cx >= World.MAP_WIDTH || cy >= World.MAP_HEIGHT || cz >= World.MAP_DEPTH) {
                        continue;
                    }
                    Item item = World.getCell(cx, cy, cz).getItem();
                    if (item != null && TREES.contains(item.getIniHeader())) {
                        World.getCell(cx, cy, cz).setEntity(null);
                    }
                }
            }
        }
    }

    /**
     * Places a machine that puts things out on the nearest cell whose eight
     * neighbors can all take what it makes, and keeps those cells clear.
     *
     * @return false when no such cell is near enough
     */
    private static boolean placeWithRoom(ItemManagerItem imi, int x, int y, int z, HashSet<Integer> hsKeepClear) {
        ItemManagerItem imiMade = ItemManager.getItem(imi.getSpawn());
        if (imiMade == null) {
            return false;
        }
        for (int iRadius = 2; iRadius <= 12; iRadius++) {
            for (int dy = -iRadius; dy <= iRadius; dy++) {
                for (int dx = -iRadius; dx <= iRadius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) == iRadius && hasRoomAround(imiMade, x + dx, y + dy, z, hsKeepClear) && placeItem(imi.getIniHeader(), x + dx, y + dy, z)) {
                        for (int ny = y + dy - 1; ny <= y + dy + 1; ny++) {
                            for (int nx = x + dx - 1; nx <= x + dx + 1; nx++) {
                                hsKeepClear.add(cellKey(nx, ny));
                            }
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasRoomAround(ItemManagerItem imiMade, int x, int y, int z, HashSet<Integer> hsKeepClear) {
        for (int ny = y - 1; ny <= y + 1; ny++) {
            for (int nx = x - 1; nx <= x + 1; nx++) {
                if (hsKeepClear.contains(cellKey(nx, ny))) {
                    return false;
                }
                if ((nx != x || ny != y) && !Item.isCellAvailableForItem(imiMade, nx, ny, z, false, false)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Integer cellKey(int x, int y) {
        return Integer.valueOf(y * World.MAP_WIDTH + x);
    }

    /**
     * Each rack that ran last turn and whose type has a dcEventPCT rolls the
     * dice; a hit swaps it for one of the event racks.  The rolls happen
     * before any swap, in install order, so a seeded game stays repeatable.
     * In a tutorial there are none until the player has reached its second to
     * last step ("Service calls" in The basics): one took down the rack the
     * player had just installed in step 5, nine steps before any word on it.
     */
    private static void rollServiceEvents() {
        // A sold site has no customers to call
        if (Buyout.isSold()) {
            return;
        }
        MissionData mission = Game.getCurrentMissionData();
        if (mission != null && mission.getTutorialFlows() != null && mission.getTutorialFlows().size() > 1
                && ImagesPanel.getMaxFlowIndex() < mission.getTutorialFlows().size() - 2) {
            return;
        }
        ArrayList<Item> alHit = new ArrayList<Item>();
        for (Integer iID : alRunningRackIDs) {
            Item rack = Item.getItemByID(iID.intValue());
            if (rack == null || !isInstalled(rack)) {
                continue;
            }
            ItemManagerItem imi = ItemManager.getItem(rack.getIniHeader());
            if (imi != null && imi.getDcEventPCT() > 0 && Utils.getRandomBetween(1, 100) <= imi.getDcEventPCT()) {
                alHit.add(rack);
            }
        }
        for (Item rack : alHit) {
            int iEvent = Utils.getRandomBetween(0, EVENT_RACKS.length - 1);
            Point3DShort p3d = replaceRack(rack, EVENT_RACKS[iEvent]);
            if (p3d != null) {
                MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString(EVENT_KEYS[iEvent]), ColorGL.ORANGE, p3d);
            }
        }
    }

    /**
     * Swaps an installed rack for another rack type in the same cell, the way
     * a job's replaceCellItem does.
     *
     * @return where the new rack stands, or null when it could not be made
     */
    static Point3DShort replaceRack(Item rack, String sIniHeader) {
        ItemManagerItem imi = ItemManager.getItem(sIniHeader);
        if (imi == null) {
            Log.log(Log.LEVEL_ERROR, "Data center: unknown rack [" + sIniHeader + "]", "DataCenter"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        short x = rack.getX();
        short y = rack.getY();
        short z = rack.getZ();
        boolean bLocked = rack.isLocked();
        int iFace = rack.getFacing();
        rack.delete();
        Item item = Item.createItem(imi);
        item.setOperative(true);
        item.setLocked(bLocked);
        item.init(x, y, z);
        if (imi.isCanBeRotated()) {
            item.setFacing(iFace);
        }
        World.getCell(x, y, z).setEntity(item);
        return new Point3DShort(x, y, z);
    }

    /**
     * @return every installed rack type and how many there are, for the
     *         headless log (it names the worn racks the player cannot see)
     */
    private static String describeRacks() {
        TreeMap<String, Integer> tmCount = new TreeMap<String, Integer>();
        for (Item item : World.getItems().values()) {
            ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
            if (imi != null && imi.getDcIncome() > 0 && isInstalled(item)) {
                Integer iCount = tmCount.get(item.getIniHeader());
                tmCount.put(item.getIniHeader(), iCount == null ? 1 : iCount + 1);
            }
        }
        return tmCount.toString();
    }

    private static boolean placeItem(String sIniHeader, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= World.MAP_WIDTH || y >= World.MAP_HEIGHT || z >= World.MAP_DEPTH) {
            return false;
        }
        ItemManagerItem imi = ItemManager.getItem(sIniHeader);
        if (imi == null) {
            Log.log(Log.LEVEL_ERROR, "Data center starting kit: unknown item [" + sIniHeader + "]", "DataCenter"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        }
        if (Item.shouldFall(World.getCells(), imi, Point3DShort.getPoolInstance(x, y, z)) || !Item.isCellAvailableForItem(imi, x, y, z, false, false)) {
            return false;
        }
        Item item = Item.createItem(imi);
        item.setOperative(true);
        // Installed gear is locked in place like placed furniture; an
        // unlocked item is loose stock that orders may pick up and move
        item.setLocked(imi.isDataCenter() || imi.getDcNet() > 0 || imi.isLocked());
        // No init here, as in ItemGenerator: once the world is made,
        // Game.startGame inits every item on the map (Item.loadItems), and
        // a second init listed each piece twice in the item counts
        World.getCell(x, y, z).setEntity(item);
        return true;
    }

    /**
     * @return whether this is gear the build menu installs (racks, power,
     *         cooling, switches, machines, guard posts, cameras, water gear),
     *         which does nothing until it is installed (locked in its cell)
     */
    /**
     * @return whether a click on any part of the item's picture means the
     *         item (MainPanel.getGearUnderMouse): gear, and a rack waiting to
     *         be wiped, which earns nothing and so is not gear to the hall
     */
    public static boolean isPickedByPicture(ItemManagerItem imi) {
        return isGear(imi) || MoveOuts.VACATED_RACK.equals(imi.getIniHeader());
    }

    static boolean isGear(ItemManagerItem imi) {
        return imi.isDataCenter() || imi.getDcSecurity() > 0 || imi.getDcWater() > 0 || imi.getDcNet() > 0;
    }

    /**
     * @return whether the item lies in a cell of the map unlocked (not
     *         carried, not installed)
     */
    private static boolean liesLoose(Item item) {
        if (item.isLocked()) {
            return false;
        }
        Point3DShort p3d = item.getCoordinates();
        if (p3d == null || p3d.x < 0 || p3d.y < 0 || p3d.z < 0 || p3d.x >= World.MAP_WIDTH || p3d.y >= World.MAP_HEIGHT || p3d.z >= World.MAP_DEPTH) {
            return false;
        }
        Cell cell = World.getCell(p3d);
        return cell != null && cell.getEntity() == item;
    }

    static boolean isInstalled(Item item) {
        if (!item.isLocked()) {
            return false;
        }
        Point3DShort p3d = item.getCoordinates();
        if (p3d == null || p3d.x < 0 || p3d.y < 0 || p3d.z < 0 || p3d.x >= World.MAP_WIDTH || p3d.y >= World.MAP_HEIGHT || p3d.z >= World.MAP_DEPTH) {
            return false;
        }
        Cell cell = World.getCell(p3d);
        return cell != null && cell.getEntity() == item;
    }

    private static void setRunning(Item item, boolean bRunning) {
        if (item.isOperative() != bRunning) {
            item.setOperative(bRunning);
        }
    }

    private static String getDownReport() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < racksDown.length; i++) {
            if (racksDown[i] > 0) {
                if (sb.length() > 0) {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(MessageFormat.format(Messages.getString(DOWN_KEYS[i]), racksDown[i]));
            }
        }
        return sb.toString();
    }

    private static void reportDownRacks() {
        String sReport = getDownReport();
        if (sReport.equals(lastDownReport)) {
            return;
        }
        if (sReport.length() > 0) {
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.down"), sReport), ColorGL.ORANGE); //$NON-NLS-1$
        } else if (racks > 0) {
            MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString("DataCenter.allUp"), ColorGL.GREEN); //$NON-NLS-1$
        }
        lastDownReport = sReport;
    }
}
