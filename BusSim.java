import java.io.*;
import java.util.*;

/**
 * CS520 – Homework 3: Discrete Event Bus Simulation
 * -------------------------------------------------
 * Simulates a circular bus route using a discrete-event system from the lecture.
 * 
 * Features:
 * - 15 stops (default), 5 buses (default)
 * - Poisson passenger arrivals (λ = 2.5/min per stop)
 * - Deterministic 5-minute travel time between stops
 * - 2-second boarding per passenger
 * - Optional "hold" control to maintain even bus spacing (headway control)
 * 
 * Generates four CSV output files for analysis:
 *   1. stop_stats.csv   – queue statistics per stop
 *   2. bus_stats.csv    – onboard statistics per bus
 *   3. headways.csv     – headway distances between buses
 *   4. snapshots.csv    – periodic logs of bus position and load
 */
public class BusSim {

    // ---------------- Simulation Parameters ----------------
    static int NUM_STOPS = 15;               // total number of stops on the route
    static int NUM_BUSES = 5;                // number of buses in service
    static double DRIVE_TIME_MIN = 5.0;      // travel time between stops (minutes)
    static double BOARD_SEC = 2.0;           // boarding time per passenger (seconds)
    static double LAMBDA_PER_MIN = 2.5;      // average passenger arrivals per minute
    static double HOURS = 8.0;               // total simulation time (hours)
    static long SEED = 42L;                  // random seed for reproducibility
    static double SNAPSHOT_EVERY_SEC = 60.0; // frequency of data collection (seconds)

    // Headway control parameters (optional)
    enum ControlMode { NONE, HOLD }
    static ControlMode MODE = ControlMode.NONE; // control mode
    static double ALPHA = 1.0;                  // target headway multiplier (1.0 = perfect spacing)
    static double MAX_HOLD_SEC = 90.0;          // max hold time per stop (seconds)

    // ---------------- Utility Functions ----------------
    static double minToSec(double m){ return m * 60.0; }  // converts minutes to seconds
    static double secToMin(double s){ return s / 60.0; }  // converts seconds to minutes

    // ---------------- Simulation Entities ----------------

    /**
     * Stop – represents a bus stop with a passenger queue.
     * Tracks queue size over time to compute average waiting line.
     */
    static class Stop {
        final int id;
        int q = 0;                // current queue length
        double lastChange = 0.0;  // time of last queue size update
        double areaQ = 0.0;       // accumulated area under q(t)
        int qMin = 0, qMax = 0;   // min/max observed queue length

        Stop(int id){ this.id = id; }

        // updates time-weighted area for queue statistics
        void update(double now){ areaQ += q * (now - lastChange); lastChange = now; }

        // passenger arrives → increase queue
        void arrive(double now){ update(now); q++; if(q > qMax) qMax = q; }

        // passenger boards → decrease queue
        void depart(double now){ update(now); if(q > 0) q--; if(q < qMin) qMin = q; }

        // compute average queue length for entire simulation
        double avgQ(double horizon){ return areaQ / horizon; }
    }

    /**
     * Bus – represents a bus moving between stops.
     * Tracks onboard passengers and times for headway calculations.
     */
    static class Bus {
        final int id;
        int stopIdx;                 // current stop index
        int onboard = 0;             // current number of passengers onboard
        int maxOnboard = 0;          // max passengers onboard observed
        long totalBoarded = 0;       // total passengers served by this bus
        double lastMoveTime = 0.0;   // last time bus departed a stop

        Bus(int id, int startStop, double startTime){
            this.id = id;
            this.stopIdx = startStop;
            this.lastMoveTime = startTime;
        }
    }

    /**
     * Event – represents an event scheduled in the priority queue.
     * Types: PERSON (arrival), ARRIVAL (bus reaches stop),
     * BOARDER (passenger boarding), SNAPSHOT (data logging).
     */
    enum EventType { PERSON, ARRIVAL, BOARDER, SNAPSHOT }

    static class Event implements Comparable<Event> {
        final double t;        // time of event (seconds)
        final EventType type;  // type of event
        final int stopId;      // which stop
        final int busId;       // which bus (if any)

        Event(double t, EventType type, int stopId, int busId){
            this.t = t; this.type = type; this.stopId = stopId; this.busId = busId;
        }

        // ensures events are processed in chronological order
        public int compareTo(Event o){ return Double.compare(this.t, o.t); }
    }

    /**
     * ExpRng – generates exponential interarrival times for passenger arrivals.
     * Uses inverse transform sampling: T = -ln(1-U)/λ
     */
    static class ExpRng {
        final Random r;
        final double lambdaPerSec;

        ExpRng(long seed, double lambdaPerMin){
            r = new Random(seed);
            lambdaPerSec = lambdaPerMin / 60.0;
        }

        // generate next interarrival time (seconds)
        double nextInterarrivalSec(){
            double u = r.nextDouble(); 
            return -Math.log(1.0 - u) / lambdaPerSec;
        }
    }

    // ---------------- Global Simulation State ----------------
    static PriorityQueue<Event> pq = new PriorityQueue<>(); // event queue (min-heap)
    static List<Stop> stops = new ArrayList<>();            // all stops
    static List<Bus> buses = new ArrayList<>();             // all buses
    static ExpRng rng;                                      // random number generator (rng)

    // output writers
    static PrintWriter snapOut, stopOut, busOut, headwayOut;
    static double[] onboardSum; static long[] onboardCnt;    // for average onboard calc

    // ---------------- Main Program ----------------
    public static void main(String[] args) throws Exception {
        parseArgs(args);     // read CLI arguments
        setupLogs();         // initialize output files
        runSim();            // execute simulation loop
        writeFinalStats();   // save results
        closeLogs();         // close files (otherwise it just bricks itself on my end)
    }

    // ---------------- Argument Parser ----------------
    static void parseArgs(String[] args){
        for(int i=0;i<args.length;i++){
            switch(args[i]){
                case "--hours": HOURS = Double.parseDouble(args[++i]); break;
                case "--seed": SEED = Long.parseLong(args[++i]); break;
                case "--mode": MODE = args[++i].equalsIgnoreCase("hold")? ControlMode.HOLD : ControlMode.NONE; break;
                case "--maxHoldSec": MAX_HOLD_SEC = Double.parseDouble(args[++i]); break;
                case "--alpha": ALPHA = Double.parseDouble(args[++i]); break;
                case "--lambda": LAMBDA_PER_MIN = Double.parseDouble(args[++i]); break;
                case "--buses": NUM_BUSES = Integer.parseInt(args[++i]); break;
                case "--stops": NUM_STOPS = Integer.parseInt(args[++i]); break;
            }
        }
    }

    // ---------------- File Setup Stuff ----------------
    static void setupLogs() throws IOException {
        snapOut = new PrintWriter(new FileWriter("snapshots.csv"));
        snapOut.println("time_sec,bus_id,stop_idx,onboard,headway_min");

        stopOut = new PrintWriter(new FileWriter("stop_stats.csv"));
        stopOut.println("stop_id,avg_q,q_min,q_max");

        busOut = new PrintWriter(new FileWriter("bus_stats.csv"));
        busOut.println("bus_id,avg_onboard_est,max_onboard,total_boarded");

        headwayOut = new PrintWriter(new FileWriter("headways.csv"));
        headwayOut.println("time_sec,bus_id,headway_min");
    }

    static void closeLogs(){ snapOut.close(); stopOut.close(); busOut.close(); headwayOut.close(); }

    // ---------------- Simulation Core ----------------
    static void runSim(){
        final double HZ = HOURS * 3600.0;          // total time (seconds)
        final double DRIVE_SEC = minToSec(DRIVE_TIME_MIN);
        rng = new ExpRng(SEED, LAMBDA_PER_MIN);    // init random generator

        // initialize stops and buses
        stops.clear(); buses.clear(); pq.clear();
        for(int s=0;s<NUM_STOPS;s++) stops.add(new Stop(s));
        onboardSum = new double[NUM_BUSES]; onboardCnt = new long[NUM_BUSES];

        // compute loop time and target headway (used for spacing)
        double loopSec = NUM_STOPS * DRIVE_SEC;
        double targetHeadwaySec = loopSec / NUM_BUSES;

        // create evenly spaced buses
        for(int b=0;b<NUM_BUSES;b++){
            int startStop = (int)Math.floor((b * (double)NUM_STOPS) / NUM_BUSES) % NUM_STOPS;
            double startTime = b * targetHeadwaySec;
            Bus bus = new Bus(b, startStop, startTime);
            buses.add(bus);
            pq.add(new Event(startTime, EventType.ARRIVAL, startStop, b));
        }

        // create initial passenger arrival events and periodic snapshots
        for(int s=0;s<NUM_STOPS;s++) pq.add(new Event(0.0, EventType.PERSON, s, -1));
        for(double t=0;t<=HZ;t+=SNAPSHOT_EVERY_SEC) pq.add(new Event(t, EventType.SNAPSHOT, -1, -1));

        // process events until simulation ends
        while(!pq.isEmpty()){
            Event e = pq.poll();
            if(e.t > HZ) break; // stop if beyond horizon

            switch(e.type){
                case PERSON: handlePerson(e); break;
                case ARRIVAL: handleArrival(e, DRIVE_SEC, targetHeadwaySec); break;
                case BOARDER: handleBoarder(e, DRIVE_SEC); break;
                case SNAPSHOT: handleSnapshot(e, targetHeadwaySec); break;
            }
        }

        // finalize queue statistics
        for(Stop st: stops) st.update(HZ);
    }

    // ---------------- Event Handlers ----------------

    // Handles passenger arrivals using exponential inter-arrival distribution
    static void handlePerson(Event e){
        Stop st = stops.get(e.stopId);
        st.arrive(e.t);
        double next = e.t + rng.nextInterarrivalSec(); // schedule next passenger
        pq.add(new Event(next, EventType.PERSON, e.stopId, -1));
    }

    // Handles bus arrivals at stops
    static void handleArrival(Event e, double DRIVE_SEC, double targetHeadwaySec){
        Bus bus = buses.get(e.busId);
        bus.stopIdx = e.stopId;
        Stop st = stops.get(e.stopId);

        // log spacing (headway) data
        double hwMin = headwayMin(bus, e.t, targetHeadwaySec);
        headwayOut.printf(Locale.US, "%f,%d,%.6f%n", e.t, bus.id, hwMin);

        if(st.q == 0){
            // stop empty → maybe hold for spacing if control enabled
            double hold = (MODE == ControlMode.HOLD) ? computeHoldSeconds(bus, e.t, targetHeadwaySec) : 0.0;
            double depart = e.t + Math.min(hold, MAX_HOLD_SEC);

            int nextStop = (e.stopId + 1) % NUM_STOPS;
            bus.lastMoveTime = depart;
            pq.add(new Event(depart + DRIVE_SEC, EventType.ARRIVAL, nextStop, bus.id));
        } else {
            // passengers waiting → start boarding immediately
            pq.add(new Event(e.t, EventType.BOARDER, e.stopId, bus.id));
        }
    }

    // Handles passenger boarding events
    static void handleBoarder(Event e, double DRIVE_SEC){
        Bus bus = buses.get(e.busId);
        Stop st = stops.get(e.stopId);

        if(st.q > 0){
            st.depart(e.t);
            bus.onboard++;
            if(bus.onboard > bus.maxOnboard) bus.maxOnboard = bus.onboard;
            bus.totalBoarded++;

            // continue boarding or depart when done
            if(st.q > 0)
                pq.add(new Event(e.t + BOARD_SEC, EventType.BOARDER, e.stopId, bus.id));
            else {
                int nextStop = (e.stopId + 1) % NUM_STOPS;
                bus.lastMoveTime = e.t;
                pq.add(new Event(e.t + DRIVE_SEC, EventType.ARRIVAL, nextStop, bus.id));
            }
        } else {
            // safety case: if no one waiting, just move on
            int nextStop = (e.stopId + 1) % NUM_STOPS;
            bus.lastMoveTime = e.t;
            pq.add(new Event(e.t + DRIVE_SEC, EventType.ARRIVAL, nextStop, bus.id));
        }
    }

    // Records bus positions and onboard data at periodic intervals
    static void handleSnapshot(Event e, double targetHeadwaySec){
        for(Bus b : buses){
            double hwMin = headwayMin(b, e.t, targetHeadwaySec);
            snapOut.printf(Locale.US, "%f,%d,%d,%d,%.6f%n", e.t, b.id, b.stopIdx, b.onboard, hwMin);
            onboardSum[b.id] += b.onboard; onboardCnt[b.id]++;
        }
    }

    // ---------------- Helper Functions ----------------

    // Calculates time gap (headway) between this bus and its predecessor
    static double headwayMin(Bus b, double now, double targetHeadwaySec){
        int predId = (b.id - 1 + NUM_BUSES) % NUM_BUSES;
        Bus pred = buses.get(predId);
        double dt = now - pred.lastMoveTime;
        double loopSec = NUM_STOPS * minToSec(DRIVE_TIME_MIN);
        while(dt < 0) dt += loopSec; // wrap around loop if needed
        return secToMin(dt);
    }

    // Computes hold time needed to maintain target headway
    static double computeHoldSeconds(Bus b, double now, double targetHeadwaySec){
        double currentMin = headwayMin(b, now, targetHeadwaySec);
        double targetMin = ALPHA * secToMin(targetHeadwaySec);
        double deficitMin = Math.max(0.0, targetMin - currentMin);
        return deficitMin * 60.0;
    }

    // ---------------- Final Output ----------------
    static void writeFinalStats(){
        double horizonSec = HOURS * 3600.0;

        // per-stop queue stats
        for(Stop st: stops)
            stopOut.printf(Locale.US, "%d,%.6f,%d,%d%n", st.id, st.avgQ(horizonSec), st.qMin, st.qMax);

        // per-bus load stats
        for(Bus b: buses){
            double avgOn = (onboardCnt[b.id] == 0) ? 0.0 : (onboardSum[b.id] / onboardCnt[b.id]);
            busOut.printf(Locale.US, "%d,%.6f,%d,%d%n", b.id, avgOn, b.maxOnboard, b.totalBoarded);
        }
    }
}