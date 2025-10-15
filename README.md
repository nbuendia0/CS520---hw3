# CS520 – Homework 3: Discrete-Event Bus System Simulation
Name: Nicolas Buendia

Course: CS 520 – Introduction to Operating Systems

Date: October 11, 2025

## Overview

This project simulates a circular bus route using a discrete-event system written in Java.

It models:
- Poisson passenger arrivals (λ = 2.5 passengers/min)
- Deterministic 5-minute travel time between stops
- 2-second boarding time per passenger

Two modes were simulated:
1. Baseline (no control)
2. Headway control (hold policy) with 90-second max hold time

The program outputs all results in CSV format for further analysis.

## Files Included

BusSim.java           - Main simulation program
parameters.txt        - Notes on parameters used
out_baseline/         - Baseline simulation results (CSV files)
out_hold/             - Headway control results (CSV files)
Report.pdf            - Final report including plots and recommendations


## How to Compile and Run

javac BusSim.java

### Run baseline
java -cp . BusSim --hours 8 --seed 42 --mode baseline
mkdir -p out_baseline
mv *.csv out_baseline/

### Run hold (headway control)
java -cp . BusSim --hours 8 --seed 42 --mode hold --maxHoldSec 90 --alpha 1.0
mkdir -p out_hold
mv *.csv out_hold/

## Program Output

Each run generates:
  headways.csv   - Time gaps (headways) between consecutive buses
  bus_stats.csv  - Average/max onboard passengers per bus
  stop_stats.csv - Queue statistics (avg, min, max per stop)
  snapshots.csv  - System state logs at 1-minute intervals

## Notes

- No external input files are required.
- The simulation parameters can be adjusted via command-line flags.
- Output data supports the analysis and recommendations in the Report.pdf file.

End of README.txt
