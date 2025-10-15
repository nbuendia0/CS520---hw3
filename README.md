# CS520 – Homework 3: Discrete-Event Bus System Simulation
Nicolas Buendia

CS 520 

Homework #3 (Bus Simulation)  

October 11, 2025

---

## Overview
This project implements a discrete-event simulation of a circular bus system with Poisson passenger arrivals and deterministic travel times.

Two policies were tested:
- **Baseline (No Control)**
- **Headway Control (Hold Policy)**

The simulation outputs CSV files for analysis (`headways.csv`, `bus_stats.csv`, etc.).

---

## How to Run
```bash
javac BusSim.java
java -cp . BusSim --hours 8 --seed 42 --mode baseline
mkdir -p out_baseline
mv *.csv out_baseline/

java -cp . BusSim --hours 8 --seed 42 --mode hold --maxHoldSec 90 --alpha 1.0
mkdir -p out_hold
mv *.csv out_hold/
