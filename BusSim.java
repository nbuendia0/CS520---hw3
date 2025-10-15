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

    // ---------------- Parameters (overridable via CLI) ----------------
    static int NUM_STOPS = 15;
    static int NUM_BUSES = 5;
    static double DRIVE_TIME_MIN = 5.0;   // min between adjacent stops
    static double BOARD_SEC = 2.0;        // sec per boarding passenger
    static double LAMBDA_PER_MIN = 2.5;   // Poisson arrivals per minute per stop
    static double HOURS = 8.0;            // horizon (hours)
    static long SEED = 42L;
    static double SNAPSHOT_EVERY_SEC = 60.0;

    enum ControlMode { NONE, HOLD }
    static ControlMode MODE = ControlMode.NONE;
    static double ALPHA = 1.0;            // target headway multiplier
    static double MAX_HOLD_SEC = 90.0;    // cap per-stop hold

    // ---- Alighting realism patch ----
    static double ALIGHT_SEC = 1.0;       // sec per alighting passenger (dwell)
    static double AVG_TRIP_STOPS = 5.0;   // expected # of stops a rider stays onboard

    // ---------------- Helpers ----------------
    static double minToSec(double m){ return m * 60.0; }
    static double secToMin(double s){ return s / 60.0; }

    // Binomial sampler for small n (simple loop; fine for our sizes)
    static int binomial(Random r, int n, double p){
        if (p <= 0) return 0;
        if (p >= 1) return n;
        int x = 0;
        for(int i=0;i<n;i++) if(r.nextDouble() < p) x++;
        return x;
    }

    // ---------------- Entities ----------------
    static class Stop {
        final int id;
        int q = 0;
        double lastChange = 0.0;
        double areaQ = 0.0;
        int qMin = 0, qMax = 0;
        Stop(int id){ this.id = id; }
        void update(double now){ areaQ += q * (now - lastChange); lastChange = now; }
        void arrive(double now){ update(now); q++; if(q > qMax) qMax = q; }
        void depart(double now){ update(now); if(q > 0) q--; if(q < qMin) qMin = q; }
        double avgQ(double horizon){ return areaQ / horizon; }
    }

    static class Bus {
        final int id;
        int stopIdx;
        int onboard = 0;
        int maxOnboard = 0;
        long totalBoarded = 0;
        double lastMoveTime = 0.0; // last departure timestamp (sec)
        Bus(int id, int startStop, double startTime){
            this.id=id; this.stopIdx=startStop; this.lastMoveTime=startTime;
        }
    }

    enum EventType { PERSON, ARRIVAL, BOARDER, SNAPSHOT }

    static class Event implements Comparable<Event> {
        final double t; final EventType type; final int stopId; final int busId;
        Event(double t, EventType type, int stopId, int busId){
            this.t=t; this.type=type; this.stopId=stopId; this.busId=busId;
        }
        public int compareTo(Event o){ return Double.compare(this.t, o.t); }
    }

    static class ExpRng {
        final Random r; final double lambdaPerSec;
        ExpRng(long seed, double lambdaPerMin){ r=new Random(seed); lambdaPerSec=lambdaPerMin/60.0; }
        double nextInterarrivalSec(){ double u=r.nextDouble(); return -Math.log(1.0-u)/lambdaPerSec; }
    }

    // ---------------- Global state ----------------
    static PriorityQueue<Event> pq = new PriorityQueue<>();
    static List<Stop> stops = new ArrayList<>();
    static List<Bus> buses = new ArrayList<>();
    static ExpRng rng;

    // logging
    static PrintWriter snapOut, stopOut, busOut, headwayOut;
    static double[] onboardSum; static long[] onboardCnt;

    // ---------------- Main ----------------
    public static void main(String[] args) throws Exception {
        parseArgs(args);
        setupLogs();
        runSim();
        writeFinalStats();
        closeLogs();
    }

    // ---------------- CLI parsing ----------------
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
                case "--alightSec": ALIGHT_SEC = Double.parseDouble(args[++i]); break;
                case "--avgTripStops": AVG_TRIP_STOPS = Double.parseDouble(args[++i]); break;
            }
        }
    }

    // ---------------- Output setup ----------------
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
    static void closeLogs(){ try{ snapOut.close(); stopOut.close(); busOut.close(); headwayOut.close(); }catch(Exception ignored){} }

    // ---------------- Simulation core ----------------
    static void runSim(){
        final double HZ = HOURS * 3600.0;
        final double DRIVE_SEC = minToSec(DRIVE_TIME_MIN);
        rng = new ExpRng(SEED, LAMBDA_PER_MIN);

        // init entities
        stops.clear(); buses.clear(); pq.clear();
        for(int s=0;s<NUM_STOPS;s++) stops.add(new Stop(s));
        onboardSum = new double[NUM_BUSES]; onboardCnt = new long[NUM_BUSES];

        // even spacing
        double loopSec = NUM_STOPS * DRIVE_SEC;
        double targetHeadwaySec = loopSec / NUM_BUSES;

        for(int b=0;b<NUM_BUSES;b++){
            int startStop = (int)Math.floor((b * (double)NUM_STOPS) / NUM_BUSES) % NUM_STOPS;
            double startTime = b * targetHeadwaySec;
            Bus bus = new Bus(b, startStop, startTime);
            buses.add(bus);
            pq.add(new Event(startTime, EventType.ARRIVAL, startStop, b));
        }

        // seed arrivals & snapshots
        for(int s=0;s<NUM_STOPS;s++) pq.add(new Event(0.0, EventType.PERSON, s, -1));
        for(double t=0.0; t<=HZ; t+=SNAPSHOT_EVERY_SEC) pq.add(new Event(t, EventType.SNAPSHOT, -1, -1));

        // main loop
        while(!pq.isEmpty()){
            Event e = pq.poll();
            if(e.t > HZ) break;
            switch(e.type){
                case PERSON:  handlePerson(e); break;
                case ARRIVAL: handleArrival(e, DRIVE_SEC, targetHeadwaySec); break;
                case BOARDER: handleBoarder(e, DRIVE_SEC); break;
                case SNAPSHOT: handleSnapshot(e, targetHeadwaySec); break;
            }
        }

        // finalize queue integrals
        for(Stop st: stops) st.update(HZ);
    }

    // ---------------- Event handlers ----------------
    static void handlePerson(Event e){
        Stop st = stops.get(e.stopId);
        st.arrive(e.t);
        double next = e.t + rng.nextInterarrivalSec();
        pq.add(new Event(next, EventType.PERSON, e.stopId, -1));
    }

    static void handleArrival(Event e, double DRIVE_SEC, double targetHeadwaySec){
        Bus bus = buses.get(e.busId);
        bus.stopIdx = e.stopId;
        Stop st = stops.get(e.stopId);

        // log headway at arrival
        double hwMin = headwayMin(bus, e.t, targetHeadwaySec);
        headwayOut.printf(Locale.US, "%f,%d,%.6f%n", e.t, bus.id, hwMin);

        // ---- A L I G H T I N G (binomial proxy) ----
        double pAlight = 1.0 / Math.max(1.0, AVG_TRIP_STOPS);
        int toAlight = binomial(rng.r, bus.onboard, pAlight);
        double afterAlightTime = e.t + toAlight * ALIGHT_SEC;
        bus.onboard -= toAlight;
        if (bus.onboard < 0) bus.onboard = 0;

        // use time after alighting as the new base time
        double t0 = afterAlightTime;

        if(st.q == 0){
            // possible hold for spacing
            double hold = (MODE == ControlMode.HOLD) ? computeHoldSeconds(bus, t0, targetHeadwaySec) : 0.0;
            double depart = t0 + Math.min(hold, MAX_HOLD_SEC);
            int nextStop = (e.stopId + 1) % NUM_STOPS;
            bus.lastMoveTime = depart;
            pq.add(new Event(depart + DRIVE_SEC, EventType.ARRIVAL, nextStop, bus.id));
        } else {
            // start boarding immediately after alighting dwell
            pq.add(new Event(t0, EventType.BOARDER, e.stopId, bus.id));
        }
    }

    static void handleBoarder(Event e, double DRIVE_SEC){
        Bus bus = buses.get(e.busId);
        Stop st = stops.get(e.stopId);

        if(st.q > 0){
            st.depart(e.t);
            bus.onboard++;
            if(bus.onboard > bus.maxOnboard) bus.maxOnboard = bus.onboard;
            bus.totalBoarded++;

            if(st.q > 0){
                pq.add(new Event(e.t + BOARD_SEC, EventType.BOARDER, e.stopId, bus.id));
            } else {
                int nextStop = (e.stopId + 1) % NUM_STOPS;
                bus.lastMoveTime = e.t;
                pq.add(new Event(e.t + DRIVE_SEC, EventType.ARRIVAL, nextStop, bus.id));
            }
        } else {
            int nextStop = (e.stopId + 1) % NUM_STOPS;
            bus.lastMoveTime = e.t;
            pq.add(new Event(e.t + DRIVE_SEC, EventType.ARRIVAL, nextStop, bus.id));
        }
    }

    static void handleSnapshot(Event e, double targetHeadwaySec){
        for(Bus b : buses){
            double hwMin = headwayMin(b, e.t, targetHeadwaySec);
            snapOut.printf(Locale.US, "%f,%d,%d,%d,%.6f%n", e.t, b.id, b.stopIdx, b.onboard, hwMin);
            onboardSum[b.id] += b.onboard; onboardCnt[b.id]++;
        }
    }

    // ---------------- Control / metrics helpers ----------------
    static double headwayMin(Bus b, double now, double targetHeadwaySec){
        int predId = (b.id - 1 + NUM_BUSES) % NUM_BUSES;
        Bus pred = buses.get(predId);
        double dt = now - pred.lastMoveTime;
        double loopSec = NUM_STOPS * minToSec(DRIVE_TIME_MIN);
        while(dt < 0) dt += loopSec; // wrap
        return secToMin(dt);
    }

    static double computeHoldSeconds(Bus b, double now, double targetHeadwaySec){
        double currentMin = headwayMin(b, now, targetHeadwaySec);
        double targetMin = ALPHA * secToMin(targetHeadwaySec);
        double deficitMin = Math.max(0.0, targetMin - currentMin);
        return deficitMin * 60.0; // to seconds
    }

    // ---------------- Final stats ----------------
    static void writeFinalStats(){
        double horizonSec = HOURS * 3600.0;
        // stops
        for(Stop st: stops){
            stopOut.printf(Locale.US, "%d,%.6f,%d,%d%n",
                    st.id, st.avgQ(horizonSec), st.qMin, st.qMax);
        }
        // buses
        for(Bus b: buses){
            double avgOn = (onboardCnt[b.id] == 0) ? 0.0 : (onboardSum[b.id] / onboardCnt[b.id]);
            busOut.printf(Locale.US, "%d,%.6f,%d,%d%n",
                    b.id, avgOn, b.maxOnboard, b.totalBoarded);
        }
    }
}
