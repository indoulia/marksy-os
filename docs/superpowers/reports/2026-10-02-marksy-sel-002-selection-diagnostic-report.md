# SEL-002 selection diagnostic

## 1. Executive conclusion

SEL-002 produced Grade A under the criteria frozen before execution (`SEL-002-EVIDENCE-002`, evidence.py SHA-256 9c0dea9e2f848334ab27b02643ebb3992b57726929a4f25fb556ebfbc2f08c57).

**STRONG EVIDENCE OF A RANKING SIGNAL.** Recommend TG-001; resuming it still needs the user's approval.

Diagnostic classification only. It is not a publication decision; SPG-001 decisions, thresholds and publication are unchanged.
The checks and flags below (core, robustness, and the major and minor flags) were frozen before any SEL-002 number was computed and are never changed afterwards; they are descriptive and must not be used to tune SEL-001.
- h=3: all core checks hold = True; positive evidence: mean IC CI_low > 0 = True, K=10 net excess CI_low > 0 = True; rules: E1 holds, E2 holds, E3 holds, E4 holds, E5 holds, E6 holds
- h=5: all core checks hold = True; positive evidence: mean IC CI_low > 0 = True, K=10 net excess CI_low > 0 = True; rules: E1 holds, E2 holds, E3 holds, E4 holds, E5 holds, E6 holds

Evidence checks, h=3, core (E1-E4, gate class A):
- E1 mean Spearman IC CI_low: 0.08494 (needs > 0) HOLDS
- E1 share of sessions with IC > 0: 0.87182 (needs >= 0.55) HOLDS
- E1 bucket monotonicity Spearman: 1.00000 (needs >= 0.8) HOLDS
- E1 top-minus-bottom decile spread CI_low: 0.01044 (needs > 0) HOLDS
- E2 K=5 net excess CI_low: 0.00537 (needs > 0) HOLDS
- E2 K=10 net excess CI_low: 0.00368 (needs > 0) HOLDS
- E2 K=25 net excess CI_low: 0.00229 (needs > 0) HOLDS
- E2 K=50 net excess CI_low: 0.00123 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-RANDOM CI_low: 0.00700 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-MOM-5 CI_low: 0.01425 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-MOM-20 CI_low: 0.01380 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-MEANREV CI_low: 0.00591 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-RELATIVE CI_low: 0.01380 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-VOLADJ CI_low: 0.00760 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-001 CI_low: 0.01081 (needs > 0) HOLDS
- E4 K=10 net excess minus the best of 8 permutation runs: 0.00855 (needs > 0) HOLDS
- E4 K=10 net excess margin over 3 permutation sd (ddof=1): 0.00758 (needs >= 0) HOLDS
- E4 mean IC minus the best of 8 permutation ICs: 0.07993 (needs > 0) HOLDS
- E4 selection-null p: 0.00100 (needs <= 0.01) HOLDS

Evidence checks, h=3, robustness (E5-E6, reported; only a major flag below gates A):
- E5 net excess CI_low without the top 1% of sessions: 0.00260 (needs > 0) HOLDS
- E5 net excess CI_low without the top 1% of stocks: 0.00227 (needs > 0) HOLDS
- E5 largest single-sector share of total net excess: 0.18796 (needs <= 0.4) HOLDS
- E5 largest single-year share of total net excess: 0.23493 (needs <= 0.4) HOLDS
- E6 direction=UP net excess CI_high: 0.01116 (needs not < 0) HOLDS
- E6 direction=DOWN net excess CI_high: 0.00233 (needs not < 0) HOLDS
- E6 volatility=LOW net excess CI_high: 0.00454 (needs not < 0) HOLDS
- E6 volatility=MID net excess CI_high: 0.00713 (needs not < 0) HOLDS
- E6 volatility=HIGH net excess CI_high: 0.01057 (needs not < 0) HOLDS
- E6 dispersion=LOW net excess CI_high: 0.00576 (needs not < 0) HOLDS
- E6 dispersion=MID net excess CI_high: 0.00684 (needs not < 0) HOLDS
- E6 dispersion=HIGH net excess CI_high: 0.00966 (needs not < 0) HOLDS
- E6 liquidity=LOW net excess CI_high: 0.00801 (needs not < 0) HOLDS
- E6 liquidity=MID net excess CI_high: 0.00591 (needs not < 0) HOLDS
- E6 liquidity=HIGH net excess CI_high: 0.00753 (needs not < 0) HOLDS
- E6 fv002=BULLISH net excess CI_high: 0.00532 (needs not < 0) HOLDS
- E6 fv002=BEARISH net excess CI_high: 0.01138 (needs not < 0) HOLDS
- E6 fv002=NEUTRAL net excess CI_high: 0.00537 (needs not < 0) HOLDS

Major red flags, h=3: none

Minor flags, h=3: none

Evidence checks, h=5, core (E1-E4, gate class A):
- E1 mean Spearman IC CI_low: 0.07806 (needs > 0) HOLDS
- E1 share of sessions with IC > 0: 0.87170 (needs >= 0.55) HOLDS
- E1 bucket monotonicity Spearman: 1.00000 (needs >= 0.8) HOLDS
- E1 top-minus-bottom decile spread CI_low: 0.01367 (needs > 0) HOLDS
- E2 K=5 net excess CI_low: 0.00882 (needs > 0) HOLDS
- E2 K=10 net excess CI_low: 0.00657 (needs > 0) HOLDS
- E2 K=25 net excess CI_low: 0.00374 (needs > 0) HOLDS
- E2 K=50 net excess CI_low: 0.00218 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-RANDOM CI_low: 0.00934 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-MOM-5 CI_low: 0.01941 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-MOM-20 CI_low: 0.02013 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-MEANREV CI_low: 0.00948 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-RELATIVE CI_low: 0.02013 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-VOLADJ CI_low: 0.01026 (needs > 0) HOLDS
- E3 K=10 paired net excess vs BASELINE-001 CI_low: 0.01362 (needs > 0) HOLDS
- E4 K=10 net excess minus the best of 8 permutation runs: 0.01386 (needs > 0) HOLDS
- E4 K=10 net excess margin over 3 permutation sd (ddof=1): 0.01277 (needs >= 0) HOLDS
- E4 mean IC minus the best of 8 permutation ICs: 0.07458 (needs > 0) HOLDS
- E4 selection-null p: 0.00100 (needs <= 0.01) HOLDS

Evidence checks, h=5, robustness (E5-E6, reported; only a major flag below gates A):
- E5 net excess CI_low without the top 1% of sessions: 0.00476 (needs > 0) HOLDS
- E5 net excess CI_low without the top 1% of stocks: 0.00432 (needs > 0) HOLDS
- E5 largest single-sector share of total net excess: 0.21946 (needs <= 0.4) HOLDS
- E5 largest single-year share of total net excess: 0.20073 (needs <= 0.4) HOLDS
- E6 direction=UP net excess CI_high: 0.01684 (needs not < 0) HOLDS
- E6 direction=DOWN net excess CI_high: 0.00551 (needs not < 0) HOLDS
- E6 volatility=LOW net excess CI_high: 0.01024 (needs not < 0) HOLDS
- E6 volatility=MID net excess CI_high: 0.01182 (needs not < 0) HOLDS
- E6 volatility=HIGH net excess CI_high: 0.01540 (needs not < 0) HOLDS
- E6 dispersion=LOW net excess CI_high: 0.01027 (needs not < 0) HOLDS
- E6 dispersion=MID net excess CI_high: 0.01287 (needs not < 0) HOLDS
- E6 dispersion=HIGH net excess CI_high: 0.01456 (needs not < 0) HOLDS
- E6 liquidity=LOW net excess CI_high: 0.01387 (needs not < 0) HOLDS
- E6 liquidity=MID net excess CI_high: 0.01139 (needs not < 0) HOLDS
- E6 liquidity=HIGH net excess CI_high: 0.01068 (needs not < 0) HOLDS
- E6 fv002=BULLISH net excess CI_high: 0.01051 (needs not < 0) HOLDS
- E6 fv002=BEARISH net excess CI_high: 0.01529 (needs not < 0) HOLDS
- E6 fv002=NEUTRAL net excess CI_high: 0.01159 (needs not < 0) HOLDS

Major red flags, h=5: none

Minor flags, h=5: none

## 2. Dataset and validation period

- Source: sqlite:///C:/Users/prsingh/AppData/Local/Temp/claude/C--AIAgent-marksy-os/6b834fe4-b26b-4339-9c36-d86bb4d99a4c/scratchpad/snapshot.db (read-only), snapshot SHA-256 5f59b9bc929f5d0c3f8fcdada419d705f0e6e9a3c0e26efb72d79ae2f0579a8b; sessions 2015-01-01 .. 2026-07-31 used, held-out from 2026-08-03 (month 2026-08) and later never read.
- Masking: rows dropped 59960, entry masked 1422, h=3 labels masked 4265, h=5 labels masked 7115
- Universe rows before the exam month: 2344563; stocks per year: 2015=683, 2016=823, 2017=954, 2018=975, 2019=723, 2020=894, 2021=1171, 2022=1347, 2023=1471, 2024=1710, 2025=1685, 2026=1643
- h=3: test months 2018-01..2026-07 (103), tested sessions 2122 (2018-01-01..2026-07-28), quarterly refits 35, protected span from index 2865; U(D) rows 1982200, resolved 1981388; excluded {'BELOW_LIQUIDITY_FLOOR': 1497838, 'DATA_CONTRACT_VIOLATION': 1386, 'NOT_EQUITY': 6713, 'TOO_FEW_BARS': 58453}
- h=5: test months 2018-01..2026-07 (103), tested sessions 2120 (2018-01-01..2026-07-24), quarterly refits 35, protected span from index 2865; U(D) rows 1979350, resolved 1977975; excluded {'BELOW_LIQUIDITY_FLOOR': 1496028, 'DATA_CONTRACT_VIOLATION': 1384, 'NOT_EQUITY': 6043, 'TOO_FEW_BARS': 58390}

## 3. SEL-001 K sensitivity

### h = 3
- K=1: trades 1533 (candidates 2122, duplicates removed 0, unresolved 2, overlap suppressed 587), sessions 1533; gross 0.01673, net 0.01373, benchmark -0.00056, net excess 0.01428 [0.00968, 0.01876]; hit rate 0.546, negative net 0.457; per-session sum median 0.00732 p10 -0.08612 p90 0.10639, top 1% share 0.306, top 5% share 0.759
- K=10: trades 13758 (candidates 21220, duplicates removed 0, unresolved 25, overlap suppressed 7437), sessions 2122; gross 0.00750, net 0.00450, benchmark -0.00054, net excess 0.00505 [0.00368, 0.00643]; hit rate 0.490, negative net 0.516; per-session sum median 0.01460 p10 -0.17894 p90 0.26126, top 1% share 0.250, top 5% share 0.798
- K=25: trades 32396 (candidates 53050, duplicates removed 0, unresolved 45, overlap suppressed 20609), sessions 2122; gross 0.00561, net 0.00261, benchmark -0.00056, net excess 0.00316 [0.00229, 0.00402]; hit rate 0.475, negative net 0.529; per-session sum median 0.02437 p10 -0.25374 p90 0.35220, top 1% share 0.248, top 5% share 0.804
- K=5: trades 7056 (candidates 10610, duplicates removed 0, unresolved 14, overlap suppressed 3540), sessions 2117; gross 0.00973, net 0.00673, benchmark -0.00068, net excess 0.00741 [0.00537, 0.00938]; hit rate 0.506, negative net 0.503; per-session sum median 0.01526 p10 -0.13046 p90 0.19265, top 1% share 0.269, top 5% share 0.801
- K=50: trades 61034 (candidates 106100, duplicates removed 0, unresolved 54, overlap suppressed 45012), sessions 2122; gross 0.00425, net 0.00125, benchmark -0.00060, net excess 0.00185 [0.00123, 0.00246]; hit rate 0.465, negative net 0.537; per-session sum median 0.02127 p10 -0.33864 p90 0.46852, top 1% share 0.291, top 5% share 0.926

### h = 5
- K=1: trades 1248 (candidates 2120, duplicates removed 0, unresolved 12, overlap suppressed 860), sessions 1248; gross 0.02274, net 0.01974, benchmark 0.00110, net excess 0.01864 [0.01182, 0.02540]; hit rate 0.527, negative net 0.469; per-session sum median 0.00670 p10 -0.10622 p90 0.17423, top 1% share 0.235, top 5% share 0.752
- K=10: trades 10374 (candidates 21200, duplicates removed 0, unresolved 35, overlap suppressed 10791), sessions 2116; gross 0.01300, net 0.01000, benchmark 0.00125, net excess 0.00875 [0.00657, 0.01094]; hit rate 0.495, negative net 0.498; per-session sum median 0.02162 p10 -0.19248 p90 0.29256, top 1% share 0.249, top 5% share 0.715
- K=25: trades 23973 (candidates 53000, duplicates removed 0, unresolved 62, overlap suppressed 28965), sessions 2120; gross 0.00935, net 0.00635, benchmark 0.00123, net excess 0.00512 [0.00374, 0.00649]; hit rate 0.479, negative net 0.511; per-session sum median 0.03228 p10 -0.27034 p90 0.42675, top 1% share 0.229, top 5% share 0.721
- K=5: trades 5424 (candidates 10600, duplicates removed 0, unresolved 29, overlap suppressed 5147), sessions 2055; gross 0.01580, net 0.01280, benchmark 0.00093, net excess 0.01187 [0.00882, 0.01492]; hit rate 0.500, negative net 0.495; per-session sum median 0.01373 p10 -0.14618 p90 0.22707, top 1% share 0.224, top 5% share 0.714
- K=50: trades 44116 (candidates 106000, duplicates removed 0, unresolved 104, overlap suppressed 61780), sessions 2120; gross 0.00713, net 0.00413, benchmark 0.00101, net excess 0.00312 [0.00218, 0.00408]; hit rate 0.469, negative net 0.520; per-session sum median 0.03922 p10 -0.35865 p90 0.51419, top 1% share 0.251, top 5% share 0.798


## 4. Prediction-decile analysis

### h = 3
- Top 1%: mean x 0.00933 [0.00776, 0.01091], pooled mean x 0.00929, median 0.00219, net excess 0.00633, rows 20840, sessions 2122
- 1-5%: mean x 0.00458 [0.00391, 0.00525], pooled mean x 0.00435, median -0.00044, net excess 0.00158, rows 79238, sessions 2122
- 5-10%: mean x 0.00307 [0.00260, 0.00355], pooled mean x 0.00302, median -0.00096, net excess 0.00007, rows 99010, sessions 2122
- 10-20%: mean x 0.00231 [0.00196, 0.00267], pooled mean x 0.00231, median -0.00150, net excess -0.00069, rows 198054, sessions 2122
- 20-30%: mean x 0.00191 [0.00162, 0.00220], pooled mean x 0.00184, median -0.00205, net excess -0.00109, rows 198222, sessions 2122
- 30-40%: mean x 0.00130 [0.00104, 0.00157], pooled mean x 0.00126, median -0.00263, net excess -0.00170, rows 198047, sessions 2122
- 40-50%: mean x 0.00092 [0.00068, 0.00116], pooled mean x 0.00084, median -0.00323, net excess -0.00208, rows 197805, sessions 2122
- 50-60%: mean x 0.00036 [0.00013, 0.00060], pooled mean x 0.00028, median -0.00382, net excess -0.00264, rows 198489, sessions 2122
- 60-70%: mean x -0.00044 [-0.00069, -0.00021], pooled mean x -0.00043, median -0.00477, net excess -0.00344, rows 198230, sessions 2122
- 70-80%: mean x -0.00118 [-0.00146, -0.00090], pooled mean x -0.00116, median -0.00587, net excess -0.00418, rows 198039, sessions 2122
- 80-90%: mean x -0.00267 [-0.00302, -0.00231], pooled mean x -0.00254, median -0.00758, net excess -0.00567, rows 198237, sessions 2122
- Bottom 10%: mean x -0.00690 [-0.00743, -0.00636], pooled mean x -0.00665, median -0.01231, net excess -0.00990, rows 197177, sessions 2122
- Monotonicity (Spearman, bottom to top): 1.000

### h = 5
- Top 1%: mean x 0.01170 [0.00907, 0.01430], pooled mean x 0.01185, median 0.00082, net excess 0.00870, rows 20802, sessions 2120
- 1-5%: mean x 0.00605 [0.00500, 0.00710], pooled mean x 0.00599, median -0.00099, net excess 0.00305, rows 79105, sessions 2120
- 5-10%: mean x 0.00420 [0.00343, 0.00496], pooled mean x 0.00413, median -0.00219, net excess 0.00120, rows 98846, sessions 2120
- 10-20%: mean x 0.00366 [0.00313, 0.00418], pooled mean x 0.00348, median -0.00227, net excess 0.00066, rows 197710, sessions 2120
- 20-30%: mean x 0.00242 [0.00197, 0.00288], pooled mean x 0.00233, median -0.00335, net excess -0.00058, rows 197862, sessions 2120
- 30-40%: mean x 0.00172 [0.00134, 0.00211], pooled mean x 0.00161, median -0.00393, net excess -0.00128, rows 197724, sessions 2120
- 40-50%: mean x 0.00103 [0.00067, 0.00138], pooled mean x 0.00090, median -0.00461, net excess -0.00197, rows 197461, sessions 2120
- 50-60%: mean x 0.00036 [0.00003, 0.00070], pooled mean x 0.00037, median -0.00527, net excess -0.00264, rows 198132, sessions 2120
- 60-70%: mean x -0.00078 [-0.00114, -0.00041], pooled mean x -0.00074, median -0.00624, net excess -0.00378, rows 197919, sessions 2120
- 70-80%: mean x -0.00164 [-0.00201, -0.00127], pooled mean x -0.00159, median -0.00750, net excess -0.00464, rows 197667, sessions 2120
- 80-90%: mean x -0.00341 [-0.00392, -0.00291], pooled mean x -0.00331, median -0.00960, net excess -0.00641, rows 197905, sessions 2120
- Bottom 10%: mean x -0.00917 [-0.01000, -0.00834], pooled mean x -0.00878, median -0.01589, net excess -0.01217, rows 196842, sessions 2120
- Monotonicity (Spearman, bottom to top): 1.000


## 5. Simple-baseline comparison

### h = 3
- BASELINE-001: K=1 -0.01009 [-0.01366, -0.00648] (n=1710); K=10 -0.00762 [-0.00890, -0.00637] (n=14564); K=25 -0.00651 [-0.00737, -0.00566] (n=34169); K=5 -0.00791 [-0.00965, -0.00619] (n=7629); K=50 -0.00594 [-0.00659, -0.00530] (n=64138)
- BASELINE-MEANREV: K=1 -0.00175 [-0.00773, 0.00425] (n=962); K=10 -0.00257 [-0.00429, -0.00086] (n=9658); K=25 -0.00211 [-0.00319, -0.00101] (n=23935); K=5 -0.00253 [-0.00500, -0.00006] (n=4804); K=50 -0.00252 [-0.00332, -0.00170] (n=47274)
- BASELINE-MOM-20: K=1 -0.01834 [-0.02236, -0.01424] (n=872); K=10 -0.01077 [-0.01242, -0.00914] (n=8627); K=25 -0.00797 [-0.00905, -0.00692] (n=21845); K=5 -0.01279 [-0.01492, -0.01055] (n=4289); K=50 -0.00591 [-0.00671, -0.00513] (n=43282)
- BASELINE-MOM-5: K=1 -0.02518 [-0.02938, -0.02082] (n=1163); K=10 -0.01120 [-0.01277, -0.00961] (n=11193); K=25 -0.00784 [-0.00887, -0.00682] (n=27322); K=5 -0.01581 [-0.01798, -0.01358] (n=5664); K=50 -0.00663 [-0.00735, -0.00591] (n=53242)
- BASELINE-RANDOM: K=1 -0.00409 [-0.00595, -0.00225] (n=2116); K=10 -0.00347 [-0.00408, -0.00284] (n=20711); K=25 -0.00298 [-0.00335, -0.00260] (n=50041); K=5 -0.00307 [-0.00397, -0.00214] (n=10480); K=50 -0.00295 [-0.00322, -0.00268] (n=94656)
- BASELINE-RELATIVE: K=1 -0.01834 [-0.02236, -0.01424] (n=872); K=10 -0.01077 [-0.01242, -0.00914] (n=8627); K=25 -0.00797 [-0.00905, -0.00692] (n=21845); K=5 -0.01279 [-0.01492, -0.01055] (n=4289); K=50 -0.00591 [-0.00671, -0.00513] (n=43282)
- BASELINE-VOLADJ: K=1 -0.00065 [-0.00548, 0.00400] (n=985); K=10 -0.00451 [-0.00594, -0.00306] (n=9766); K=25 -0.00457 [-0.00550, -0.00363] (n=23631); K=5 -0.00398 [-0.00602, -0.00193] (n=4944); K=50 -0.00383 [-0.00451, -0.00313] (n=45788)
- SEL-001: K=1 0.01428 [0.00968, 0.01876] (n=1533); K=10 0.00505 [0.00368, 0.00643] (n=13758); K=25 0.00316 [0.00229, 0.00402] (n=32396); K=5 0.00741 [0.00537, 0.00938] (n=7056); K=50 0.00185 [0.00123, 0.00246] (n=61034)
- K=10 paired difference, SEL-001 minus baseline:
  - BASELINE-001: 0.01267 [0.01081, 0.01452] over 2122 sessions, SEL-001 better in 0.636 of shared sessions
  - BASELINE-MEANREV: 0.00762 [0.00591, 0.00934] over 2122 sessions, SEL-001 better in 0.584 of shared sessions
  - BASELINE-MOM-20: 0.01582 [0.01380, 0.01780] over 2122 sessions, SEL-001 better in 0.637 of shared sessions
  - BASELINE-MOM-5: 0.01625 [0.01425, 0.01823] over 2122 sessions, SEL-001 better in 0.664 of shared sessions
  - BASELINE-RANDOM: 0.00852 [0.00700, 0.01005] over 2122 sessions, SEL-001 better in 0.592 of shared sessions
  - BASELINE-RELATIVE: 0.01582 [0.01380, 0.01780] over 2122 sessions, SEL-001 better in 0.637 of shared sessions
  - BASELINE-VOLADJ: 0.00956 [0.00760, 0.01152] over 2122 sessions, SEL-001 better in 0.594 of shared sessions
- Random seeds 1..20 mean net excess: K=1 mean -0.00278 sd 0.00093 [-0.00477, -0.00109], SEL-001 above 1.00; K=10 mean -0.00302 sd 0.00034 [-0.00375, -0.00256], SEL-001 above 1.00; K=25 mean -0.00305 sd 0.00021 [-0.00349, -0.00269], SEL-001 above 1.00; K=5 mean -0.00296 sd 0.00047 [-0.00383, -0.00226], SEL-001 above 1.00; K=50 mean -0.00304 sd 0.00014 [-0.00323, -0.00279], SEL-001 above 1.00

### h = 5
- BASELINE-001: K=1 -0.01114 [-0.01564, -0.00655] (n=1622); K=10 -0.00764 [-0.00927, -0.00596] (n=12928); K=25 -0.00647 [-0.00764, -0.00529] (n=29291); K=5 -0.00764 [-0.00999, -0.00526] (n=6910); K=50 -0.00636 [-0.00730, -0.00539] (n=53000)
- BASELINE-MEANREV: K=1 -0.00332 [-0.01260, 0.00645] (n=721); K=10 -0.00339 [-0.00586, -0.00090] (n=7189); K=25 -0.00270 [-0.00427, -0.00111] (n=17677); K=5 -0.00458 [-0.00805, -0.00107] (n=3601); K=50 -0.00256 [-0.00377, -0.00134] (n=34373)
- BASELINE-MOM-20: K=1 -0.02794 [-0.03450, -0.02107] (n=609); K=10 -0.01447 [-0.01714, -0.01180] (n=5916); K=25 -0.00973 [-0.01150, -0.00798] (n=15043); K=5 -0.01877 [-0.02238, -0.01519] (n=2891); K=50 -0.00701 [-0.00825, -0.00576] (n=29674)
- BASELINE-MOM-5: K=1 -0.03707 [-0.04318, -0.03078] (n=914); K=10 -0.01371 [-0.01601, -0.01137] (n=8713); K=25 -0.00918 [-0.01071, -0.00769] (n=20718); K=5 -0.01937 [-0.02256, -0.01615] (n=4413); K=50 -0.00757 [-0.00863, -0.00651] (n=39438)
- BASELINE-RANDOM: K=1 -0.00257 [-0.00501, -0.00012] (n=2109); K=10 -0.00291 [-0.00373, -0.00207] (n=20190); K=25 -0.00272 [-0.00324, -0.00222] (n=47366); K=5 -0.00251 [-0.00370, -0.00130] (n=10346); K=50 -0.00296 [-0.00333, -0.00260] (n=85789)
- BASELINE-RELATIVE: K=1 -0.02794 [-0.03450, -0.02107] (n=609); K=10 -0.01447 [-0.01714, -0.01180] (n=5916); K=25 -0.00973 [-0.01150, -0.00798] (n=15043); K=5 -0.01877 [-0.02238, -0.01519] (n=2891); K=50 -0.00701 [-0.00825, -0.00576] (n=29674)
- BASELINE-VOLADJ: K=1 0.00244 [-0.00598, 0.01064] (n=750); K=10 -0.00456 [-0.00692, -0.00223] (n=7311); K=25 -0.00426 [-0.00574, -0.00278] (n=17226); K=5 -0.00378 [-0.00707, -0.00045] (n=3772); K=50 -0.00376 [-0.00489, -0.00262] (n=32522)
- SEL-001: K=1 0.01864 [0.01182, 0.02540] (n=1248); K=10 0.00875 [0.00657, 0.01094] (n=10374); K=25 0.00512 [0.00374, 0.00649] (n=23973); K=5 0.01187 [0.00882, 0.01492] (n=5424); K=50 0.00312 [0.00218, 0.00408] (n=44116)
- K=10 paired difference, SEL-001 minus baseline:
  - BASELINE-001: 0.01639 [0.01362, 0.01916] over 2120 sessions, SEL-001 better in 0.612 of shared sessions
  - BASELINE-MEANREV: 0.01213 [0.00948, 0.01476] over 2120 sessions, SEL-001 better in 0.580 of shared sessions
  - BASELINE-MOM-20: 0.02321 [0.02013, 0.02622] over 2120 sessions, SEL-001 better in 0.652 of shared sessions
  - BASELINE-MOM-5: 0.02246 [0.01941, 0.02544] over 2120 sessions, SEL-001 better in 0.641 of shared sessions
  - BASELINE-RANDOM: 0.01166 [0.00934, 0.01394] over 2120 sessions, SEL-001 better in 0.586 of shared sessions
  - BASELINE-RELATIVE: 0.02321 [0.02013, 0.02622] over 2120 sessions, SEL-001 better in 0.652 of shared sessions
  - BASELINE-VOLADJ: 0.01330 [0.01026, 0.01633] over 2120 sessions, SEL-001 better in 0.588 of shared sessions
- Random seeds 1..20 mean net excess: K=1 mean -0.00272 sd 0.00154 [-0.00506, 0.00003], SEL-001 above 1.00; K=10 mean -0.00304 sd 0.00043 [-0.00382, -0.00232], SEL-001 above 1.00; K=25 mean -0.00301 sd 0.00026 [-0.00345, -0.00260], SEL-001 above 1.00; K=5 mean -0.00292 sd 0.00065 [-0.00430, -0.00174], SEL-001 above 1.00; K=50 mean -0.00300 sd 0.00021 [-0.00336, -0.00259], SEL-001 above 1.00


## 6. Feature ablation

### h = 3
- LOGO-OSC (22 features): K=10 0.00506 [0.00366, 0.00644], IC 0.0899 [0.0848, 0.0952]; group contribution FULL minus this: net excess -0.00001 [-0.00083, 0.00079], IC 0.0001 [-0.0005, 0.0007]
- LOGO-RANGE (21 features): K=10 0.00466 [0.00334, 0.00598], IC 0.0890 [0.0839, 0.0942]; group contribution FULL minus this: net excess 0.00039 [-0.00054, 0.00132], IC 0.0011 [0.0002, 0.0019]
- LOGO-REGIME (20 features): K=10 0.00487 [0.00350, 0.00626], IC 0.0895 [0.0844, 0.0947]; group contribution FULL minus this: net excess 0.00017 [-0.00063, 0.00096], IC 0.0006 [-0.0003, 0.0015]
- LOGO-RET (19 features): K=10 0.00283 [0.00137, 0.00425], IC 0.0777 [0.0724, 0.0830]; group contribution FULL minus this: net excess 0.00222 [0.00106, 0.00339], IC 0.0124 [0.0107, 0.0140]
- LOGO-TREND (18 features): K=10 0.00494 [0.00359, 0.00631], IC 0.0882 [0.0830, 0.0935]; group contribution FULL minus this: net excess 0.00011 [-0.00080, 0.00101], IC 0.0019 [0.0005, 0.0032]
- LOGO-VOL (20 features): K=10 0.00470 [0.00329, 0.00612], IC 0.0866 [0.0818, 0.0914]; group contribution FULL minus this: net excess 0.00035 [-0.00057, 0.00128], IC 0.0035 [0.0022, 0.0049]
- LOGO-VOLUME (21 features): K=10 0.00443 [0.00303, 0.00581], IC 0.0877 [0.0825, 0.0930]; group contribution FULL minus this: net excess 0.00061 [-0.00028, 0.00156], IC 0.0024 [0.0015, 0.0032]
- LOGO-XSEC (20 features): K=10 0.00471 [0.00330, 0.00612], IC 0.0891 [0.0840, 0.0941]; group contribution FULL minus this: net excess 0.00034 [-0.00049, 0.00118], IC 0.0010 [-0.0003, 0.0024]
- PRICE+VOLUME (17 features): K=10 0.00536 [0.00397, 0.00675], IC 0.0888 [0.0837, 0.0939]
- PRICE-ONLY (15 features): K=10 0.00488 [0.00338, 0.00632], IC 0.0862 [0.0809, 0.0914]
- VOLUME-ONLY (2 features): K=10 -0.00310 [-0.00368, -0.00251], IC 0.0185 [0.0164, 0.0207]
- Individual features (sign fixed per fold from training IC):
  - atr_percent: K=10 -0.00226 [-0.00343, -0.00108], IC 0.0673 [0.0576, 0.0770], negative sign in 35/35 folds
  - high_60d_distance: K=10 -0.00199 [-0.00319, -0.00078], IC 0.0236 [0.0146, 0.0324], negative sign in 0/35 folds
  - low_60d_distance: K=10 -0.00444 [-0.00541, -0.00346], IC 0.0397 [0.0339, 0.0455], negative sign in 35/35 folds
  - macd: K=10 -0.00454 [-0.00565, -0.00342], IC -0.0047 [-0.0102, 0.0005], negative sign in 11/35 folds
  - macd_hist: K=10 -0.00314 [-0.00424, -0.00203], IC 0.0214 [0.0173, 0.0258], negative sign in 35/35 folds
  - macd_signal: K=10 -0.00380 [-0.00499, -0.00259], IC 0.0049 [-0.0003, 0.0102], negative sign in 0/35 folds
  - momentum_rank_20d: K=10 -0.00315 [-0.00482, -0.00143], IC 0.0206 [0.0139, 0.0273], negative sign in 35/35 folds
  - realized_vol_20d: K=10 -0.00170 [-0.00280, -0.00057], IC 0.0646 [0.0559, 0.0733], negative sign in 35/35 folds
  - realized_vol_60d: K=10 -0.00221 [-0.00335, -0.00106], IC 0.0676 [0.0580, 0.0773], negative sign in 35/35 folds
  - regime_bearish: K=10 -0.00282 [-0.00391, -0.00175], IC n/a [n/a, n/a], negative sign in 0/35 folds
  - regime_bullish: K=10 -0.00282 [-0.00391, -0.00175], IC n/a [n/a, n/a], negative sign in 0/35 folds
  - regime_neutral: K=10 -0.00282 [-0.00391, -0.00175], IC n/a [n/a, n/a], negative sign in 0/35 folds
  - rel_strength_market_20d: K=10 -0.00315 [-0.00482, -0.00143], IC 0.0206 [0.0139, 0.0273], negative sign in 35/35 folds
  - rel_strength_sector_20d: K=10 -0.00237 [-0.00389, -0.00086], IC 0.0252 [0.0191, 0.0313], negative sign in 35/35 folds
  - return_1d: K=10 -0.00630 [-0.00743, -0.00519], IC 0.0346 [0.0304, 0.0389], negative sign in 35/35 folds
  - return_20d: K=10 -0.00315 [-0.00482, -0.00143], IC 0.0206 [0.0139, 0.0273], negative sign in 35/35 folds
  - return_5d: K=10 -0.00156 [-0.00303, -0.00008], IC 0.0390 [0.0328, 0.0451], negative sign in 35/35 folds
  - return_60d: K=10 -0.00607 [-0.00776, -0.00436], IC 0.0018 [-0.0057, 0.0093], negative sign in 30/35 folds
  - rsi_14: K=10 -0.00232 [-0.00336, -0.00127], IC 0.0187 [0.0132, 0.0244], negative sign in 35/35 folds
  - sma20_distance: K=10 -0.00257 [-0.00429, -0.00086], IC 0.0314 [0.0246, 0.0381], negative sign in 35/35 folds
  - sma50_distance: K=10 -0.00320 [-0.00501, -0.00134], IC 0.0209 [0.0134, 0.0284], negative sign in 35/35 folds
  - volume_ratio_20d: K=10 -0.00491 [-0.00585, -0.00400], IC 0.0160 [0.0124, 0.0194], negative sign in 35/35 folds
  - volume_ratio_rank: K=10 -0.00491 [-0.00585, -0.00400], IC 0.0160 [0.0124, 0.0194], negative sign in 35/35 folds

### h = 5
- LOGO-OSC (22 features): K=10 0.00797 [0.00577, 0.01021], IC 0.0837 [0.0774, 0.0900]; group contribution FULL minus this: net excess 0.00078 [-0.00048, 0.00204], IC 0.0006 [-0.0001, 0.0014]
- LOGO-RANGE (21 features): K=10 0.00780 [0.00559, 0.00998], IC 0.0827 [0.0765, 0.0888]; group contribution FULL minus this: net excess 0.00095 [-0.00053, 0.00240], IC 0.0017 [0.0006, 0.0027]
- LOGO-REGIME (20 features): K=10 0.00762 [0.00555, 0.00969], IC 0.0834 [0.0772, 0.0896]; group contribution FULL minus this: net excess 0.00113 [-0.00020, 0.00244], IC 0.0010 [-0.0002, 0.0021]
- LOGO-RET (19 features): K=10 0.00558 [0.00349, 0.00767], IC 0.0727 [0.0663, 0.0791]; group contribution FULL minus this: net excess 0.00317 [0.00153, 0.00481], IC 0.0117 [0.0099, 0.0135]
- LOGO-TREND (18 features): K=10 0.00696 [0.00500, 0.00898], IC 0.0823 [0.0756, 0.0888]; group contribution FULL minus this: net excess 0.00178 [0.00023, 0.00335], IC 0.0020 [-0.0002, 0.0043]
- LOGO-VOL (20 features): K=10 0.00662 [0.00448, 0.00873], IC 0.0825 [0.0765, 0.0883]; group contribution FULL minus this: net excess 0.00212 [0.00068, 0.00356], IC 0.0019 [0.0003, 0.0035]
- LOGO-VOLUME (21 features): K=10 0.00756 [0.00545, 0.00964], IC 0.0817 [0.0752, 0.0881]; group contribution FULL minus this: net excess 0.00119 [-0.00022, 0.00255], IC 0.0027 [0.0017, 0.0037]
- LOGO-XSEC (20 features): K=10 0.00789 [0.00563, 0.01017], IC 0.0827 [0.0764, 0.0888]; group contribution FULL minus this: net excess 0.00086 [-0.00051, 0.00227], IC 0.0017 [-0.0003, 0.0037]
- PRICE+VOLUME (17 features): K=10 0.00647 [0.00420, 0.00874], IC 0.0816 [0.0753, 0.0877]
- PRICE-ONLY (15 features): K=10 0.00567 [0.00339, 0.00793], IC 0.0787 [0.0722, 0.0849]
- VOLUME-ONLY (2 features): K=10 -0.00268 [-0.00349, -0.00189], IC 0.0154 [0.0131, 0.0178]
- Individual features (sign fixed per fold from training IC):
  - atr_percent: K=10 -0.00204 [-0.00398, 0.00004], IC 0.0645 [0.0522, 0.0769], negative sign in 35/35 folds
  - high_60d_distance: K=10 -0.00143 [-0.00315, 0.00032], IC 0.0253 [0.0140, 0.0370], negative sign in 0/35 folds
  - low_60d_distance: K=10 -0.00442 [-0.00591, -0.00294], IC 0.0399 [0.0328, 0.0471], negative sign in 35/35 folds
  - macd: K=10 -0.00518 [-0.00704, -0.00323], IC -0.0034 [-0.0104, 0.0036], negative sign in 10/35 folds
  - macd_hist: K=10 -0.00383 [-0.00563, -0.00205], IC 0.0212 [0.0157, 0.0266], negative sign in 35/35 folds
  - macd_signal: K=10 -0.00445 [-0.00648, -0.00245], IC 0.0045 [-0.0025, 0.0115], negative sign in 0/35 folds
  - momentum_rank_20d: K=10 -0.00362 [-0.00604, -0.00115], IC 0.0210 [0.0128, 0.0293], negative sign in 35/35 folds
  - realized_vol_20d: K=10 -0.00176 [-0.00352, 0.00010], IC 0.0638 [0.0529, 0.0746], negative sign in 35/35 folds
  - realized_vol_60d: K=10 -0.00284 [-0.00475, -0.00091], IC 0.0677 [0.0553, 0.0800], negative sign in 35/35 folds
  - regime_bearish: K=10 -0.00235 [-0.00427, -0.00041], IC n/a [n/a, n/a], negative sign in 0/35 folds
  - regime_bullish: K=10 -0.00235 [-0.00427, -0.00041], IC n/a [n/a, n/a], negative sign in 0/35 folds
  - regime_neutral: K=10 -0.00235 [-0.00427, -0.00041], IC n/a [n/a, n/a], negative sign in 0/35 folds
  - rel_strength_market_20d: K=10 -0.00362 [-0.00604, -0.00115], IC 0.0210 [0.0128, 0.0293], negative sign in 35/35 folds
  - rel_strength_sector_20d: K=10 -0.00235 [-0.00474, 0.00006], IC 0.0269 [0.0194, 0.0344], negative sign in 35/35 folds
  - return_1d: K=10 -0.00862 [-0.01003, -0.00721], IC 0.0301 [0.0256, 0.0345], negative sign in 35/35 folds
  - return_20d: K=10 -0.00362 [-0.00604, -0.00115], IC 0.0210 [0.0128, 0.0293], negative sign in 35/35 folds
  - return_5d: K=10 -0.00333 [-0.00522, -0.00142], IC 0.0387 [0.0313, 0.0461], negative sign in 35/35 folds
  - return_60d: K=10 -0.00741 [-0.01023, -0.00461], IC -0.0002 [-0.0095, 0.0095], negative sign in 30/35 folds
  - rsi_14: K=10 -0.00213 [-0.00364, -0.00063], IC 0.0176 [0.0105, 0.0245], negative sign in 35/35 folds
  - sma20_distance: K=10 -0.00339 [-0.00586, -0.00090], IC 0.0308 [0.0224, 0.0392], negative sign in 35/35 folds
  - sma50_distance: K=10 -0.00382 [-0.00669, -0.00093], IC 0.0204 [0.0110, 0.0296], negative sign in 35/35 folds
  - volume_ratio_20d: K=10 -0.00579 [-0.00722, -0.00437], IC 0.0114 [0.0074, 0.0153], negative sign in 35/35 folds
  - volume_ratio_rank: K=10 -0.00579 [-0.00722, -0.00437], IC 0.0114 [0.0074, 0.0153], negative sign in 35/35 folds


## 7. Null and permutation results

### h = 3
- Selection null (1000 random top-10 draws per session, seed 42, no reduction): observed 0.00634, null mean -0.00298 sd 0.00031 max -0.00202, z 29.61, p 0.0010
- Label permutation (net_excess, seeds [1, 2, 3, 4, 5, 6, 7, 8]): observed 0.00505, null mean -0.00420 sd 0.00056 max -0.00350, z 16.65, exceeds all True
- Label permutation (ic, seeds [1, 2, 3, 4, 5, 6, 7, 8]): observed 0.09009, null mean 0.00241 sd 0.00485 max 0.01016, z 18.09, exceeds all True
  - seed 1: K=10 net excess -0.00438 (n=14600), mean IC 0.0014
  - seed 2: K=10 net excess -0.00433 (n=14683), mean IC 0.0034
  - seed 3: K=10 net excess -0.00489 (n=15052), mean IC 0.0085
  - seed 4: K=10 net excess -0.00503 (n=14804), mean IC -0.0025
  - seed 5: K=10 net excess -0.00376 (n=15362), mean IC 0.0102
  - seed 6: K=10 net excess -0.00377 (n=15131), mean IC 0.0023
  - seed 7: K=10 net excess -0.00350 (n=14730), mean IC -0.0030
  - seed 8: K=10 net excess -0.00394 (n=15878), mean IC -0.0009

### h = 5
- Selection null (1000 random top-10 draws per session, seed 42, no reduction): observed 0.00897, null mean -0.00300 sd 0.00040 max -0.00153, z 29.59, p 0.0010
- Label permutation (net_excess, seeds [1, 2, 3, 4, 5, 6, 7, 8]): observed 0.00875, null mean -0.00604 sd 0.00067 max -0.00511, z 21.97, exceeds all True
- Label permutation (ic, seeds [1, 2, 3, 4, 5, 6, 7, 8]): observed 0.08439, null mean 0.00112 sd 0.00493 max 0.00980, z 16.90, exceeds all True
  - seed 1: K=10 net excess -0.00586 (n=13423), mean IC 0.0029
  - seed 2: K=10 net excess -0.00524 (n=13132), mean IC 0.0029
  - seed 3: K=10 net excess -0.00708 (n=13089), mean IC 0.0041
  - seed 4: K=10 net excess -0.00658 (n=13920), mean IC -0.0049
  - seed 5: K=10 net excess -0.00595 (n=12599), mean IC 0.0098
  - seed 6: K=10 net excess -0.00597 (n=13381), mean IC 0.0009
  - seed 7: K=10 net excess -0.00511 (n=13363), mean IC -0.0043
  - seed 8: K=10 net excess -0.00654 (n=13644), mean IC -0.0024


## 8. Concentration

### h = 3
- Total K=10 net excess 69.46138 over 13758 trades; largest sector share 0.188 (unknown sector -0.004), largest year share 0.235
- exclude top sessions (22 removed): 0.00383 [0.00260, 0.00510] over 13598 trades
- exclude top stocks (18 removed): 0.00363 [0.00227, 0.00499] over 13156 trades
- by liquidity: 3 groups, Herfindahl 0.3516, largest share 0.437, top 1% share 0.437, top 5% share 0.437; top 10: LOW 30.3453 (0.437, 5164 trades), HIGH 21.8426 (0.314, 4113 trades), MID 17.2735 (0.249, 4481 trades)
- by month: 103 groups, Herfindahl 0.0181, largest share 0.064, top 1% share 0.110, top 5% share 0.277; top 10: 2021-12 4.4412 (0.064, 136 trades), 2021-06 3.1693 (0.046, 137 trades), 2024-02 3.0664 (0.044, 150 trades), 2021-11 3.0138 (0.043, 124 trades), 2022-02 2.8588 (0.041, 139 trades), 2022-12 2.6863 (0.039, 160 trades), 2024-01 2.3385 (0.034, 144 trades), 2023-07 2.1700 (0.031, 135 trades), 2025-03 2.1516 (0.031, 125 trades), 2021-01 2.0812 (0.030, 140 trades)
- by sector: 12 groups, Herfindahl 0.1206, largest share 0.188, top 1% share 0.188, top 5% share 0.188; top 10: Industrials 13.0560 (0.188, 2964 trades), Technology 12.1867 (0.175, 1557 trades), Basic Materials 9.8282 (0.141, 2088 trades), Consumer Cyclical 6.2878 (0.091, 1838 trades), Consumer Defensive 6.2435 (0.090, 1023 trades), Financial Services 5.8940 (0.085, 1453 trades), Utilities 4.4146 (0.064, 570 trades), Healthcare 4.1757 (0.060, 874 trades), Real Estate 4.1376 (0.060, 527 trades), Communication Services 2.8888 (0.042, 549 trades)
- by session_index: 2122 groups, Herfindahl 0.0009, largest share 0.018, top 1% share 0.250, top 5% share 0.798; top 10: 2241.0 1.2499 (0.018, 9 trades), 2727.0 1.2003 (0.017, 7 trades), 2254.0 1.1208 (0.016, 9 trades), 1745.0 1.0569 (0.015, 10 trades), 2116.0 0.9503 (0.014, 7 trades), 1178.0 0.8211 (0.012, 6 trades), 1773.0 0.7995 (0.012, 6 trades), 788.0 0.7840 (0.011, 7 trades), 2253.0 0.7762 (0.011, 7 trades), 1021.0 0.7673 (0.011, 6 trades)
- by stock_id: 1703 groups, Herfindahl 0.0017, largest share 0.035, top 1% share 0.313, top 5% share 0.870; top 10: 275.0 2.3977 (0.035, 42 trades), 2785.0 1.6899 (0.024, 38 trades), 2649.0 1.4995 (0.022, 4 trades), 2571.0 1.2940 (0.019, 56 trades), 319.0 1.2176 (0.018, 29 trades), 2764.0 1.2130 (0.017, 60 trades), 2780.0 1.2082 (0.017, 36 trades), 2039.0 1.1356 (0.016, 43 trades), 1843.0 1.1321 (0.016, 21 trades), 2068.0 1.1318 (0.016, 35 trades)
- by volatility: 3 groups, Herfindahl 0.6134, largest share 0.761, top 1% share 0.761, top 5% share 0.761; top 10: HIGH 52.8500 (0.761, 9310 trades), MID 12.0705 (0.174, 2336 trades), LOW 4.5408 (0.065, 2112 trades)
- by year: 9 groups, Herfindahl 0.1465, largest share 0.235, top 1% share 0.235, top 5% share 0.235; top 10: 2021.0 16.3185 (0.235, 1625 trades), 2022.0 11.3137 (0.163, 1671 trades), 2024.0 11.0970 (0.160, 1656 trades), 2023.0 8.3790 (0.121, 1604 trades), 2018.0 6.6548 (0.096, 1525 trades), 2020.0 6.3405 (0.091, 1602 trades), 2025.0 5.5430 (0.080, 1650 trades), 2026.0 4.1434 (0.060, 938 trades), 2019.0 -0.3284 (-0.005, 1487 trades)

### h = 5
- Total K=10 net excess 90.75004 over 10374 trades; largest sector share 0.219 (unknown sector 0.003), largest year share 0.201
- exclude top sessions (22 removed): 0.00666 [0.00476, 0.00855] over 10238 trades
- exclude top stocks (17 removed): 0.00634 [0.00432, 0.00847] over 9913 trades
- by liquidity: 3 groups, Herfindahl 0.3606, largest share 0.463, top 1% share 0.463, top 5% share 0.463; top 10: LOW 41.9989 (0.463, 4088 trades), MID 27.3278 (0.301, 3330 trades), HIGH 21.4233 (0.236, 2956 trades)
- by month: 103 groups, Herfindahl 0.0174, largest share 0.056, top 1% share 0.111, top 5% share 0.259; top 10: 2024-01 5.0909 (0.056, 98 trades), 2021-12 4.9427 (0.054, 109 trades), 2021-06 4.3406 (0.048, 99 trades), 2024-03 3.5641 (0.039, 107 trades), 2026-01 2.8067 (0.031, 109 trades), 2020-06 2.7571 (0.030, 104 trades), 2024-02 2.4260 (0.027, 118 trades), 2021-11 2.3928 (0.026, 104 trades), 2025-04 2.3666 (0.026, 102 trades), 2021-01 2.3249 (0.026, 98 trades)
- by sector: 12 groups, Herfindahl 0.1292, largest share 0.219, top 1% share 0.219, top 5% share 0.219; top 10: Industrials 19.9156 (0.219, 2236 trades), Technology 14.0391 (0.155, 1113 trades), Consumer Cyclical 13.2520 (0.146, 1428 trades), Basic Materials 9.9963 (0.110, 1572 trades), Consumer Defensive 9.7031 (0.107, 771 trades), Real Estate 6.3423 (0.070, 444 trades), Healthcare 5.0764 (0.056, 645 trades), Financial Services 4.2848 (0.047, 1105 trades), Utilities 4.2762 (0.047, 421 trades), Communication Services 3.9994 (0.044, 399 trades)
- by session_index: 2116 groups, Herfindahl 0.0009, largest share 0.025, top 1% share 0.249, top 5% share 0.715; top 10: 2241.0 2.2306 (0.025, 5 trades), 2254.0 1.4198 (0.016, 9 trades), 2278.0 1.2305 (0.014, 6 trades), 2781.0 1.1850 (0.013, 8 trades), 1698.0 1.1779 (0.013, 8 trades), 959.0 1.1711 (0.013, 4 trades), 2018.0 1.1664 (0.013, 7 trades), 2285.0 1.0294 (0.011, 8 trades), 1290.0 1.0069 (0.011, 7 trades), 2225.0 0.9907 (0.011, 6 trades)
- by stock_id: 1621 groups, Herfindahl 0.0021, largest share 0.038, top 1% share 0.307, top 5% share 0.817; top 10: 275.0 3.4418 (0.038, 32 trades), 2764.0 2.8492 (0.031, 66 trades), 1843.0 2.5397 (0.028, 21 trades), 2785.0 2.4070 (0.027, 28 trades), 350.0 1.6785 (0.018, 33 trades), 2780.0 1.5174 (0.017, 25 trades), 2525.0 1.4805 (0.016, 31 trades), 2649.0 1.3861 (0.015, 1 trades), 319.0 1.2831 (0.014, 23 trades), 386.0 1.2592 (0.014, 12 trades)
- by volatility: 3 groups, Herfindahl 0.5696, largest share 0.721, top 1% share 0.721, top 5% share 0.721; top 10: HIGH 65.4040 (0.721, 6706 trades), MID 19.4554 (0.214, 2082 trades), LOW 5.8907 (0.065, 1586 trades)
- by year: 9 groups, Herfindahl 0.1443, largest share 0.201, top 1% share 0.201, top 5% share 0.201; top 10: 2021.0 18.2163 (0.201, 1239 trades), 2024.0 18.1963 (0.201, 1314 trades), 2023.0 14.8738 (0.164, 1162 trades), 2025.0 11.7616 (0.130, 1251 trades), 2022.0 10.4053 (0.115, 1249 trades), 2020.0 9.8606 (0.109, 1260 trades), 2018.0 5.6231 (0.062, 1130 trades), 2026.0 3.4255 (0.038, 707 trades), 2019.0 -1.6124 (-0.018, 1062 trades)


## 9. Regimes

### h = 3
- direction: DOWN n=6507 sessions=1011 0.00059 [-0.00113, 0.00233] IC 0.1186 [0.1110, 0.1262]; UP n=7251 sessions=1111 0.00905 [0.00702, 0.01116] IC 0.0641 [0.0578, 0.0705]
- dispersion: HIGH n=4662 sessions=707 0.00745 [0.00526, 0.00966] IC 0.0932 [0.0850, 0.1012]; LOW n=4576 sessions=708 0.00349 [0.00126, 0.00576] IC 0.0818 [0.0732, 0.0902]; MID n=4520 sessions=707 0.00415 [0.00145, 0.00684] IC 0.0953 [0.0866, 0.1038]
- fv002: BEARISH n=4655 sessions=723 0.00868 [0.00603, 0.01138] IC 0.0876 [0.0787, 0.0966]; BULLISH n=5312 sessions=818 0.00337 [0.00145, 0.00532] IC 0.0932 [0.0860, 0.1006]; NEUTRAL n=3791 sessions=581 0.00294 [0.00056, 0.00537] IC 0.0888 [0.0797, 0.0979]
- liquidity: HIGH n=4113 sessions=1809 0.00531 [0.00317, 0.00753] IC 0.0674 [0.0606, 0.0742]; LOW n=5164 sessions=1984 0.00588 [0.00378, 0.00801] IC 0.1023 [0.0971, 0.1076]; MID n=4481 sessions=1928 0.00385 [0.00181, 0.00591] IC 0.0858 [0.0802, 0.0914]
- volatility: HIGH n=4642 sessions=707 0.00792 [0.00539, 0.01057] IC 0.0904 [0.0806, 0.1001]; LOW n=4568 sessions=708 0.00251 [0.00048, 0.00454] IC 0.0926 [0.0853, 0.0999]; MID n=4548 sessions=707 0.00467 [0.00227, 0.00713] IC 0.0872 [0.0784, 0.0962]

### h = 5
- direction: DOWN n=4664 sessions=969 0.00267 [-0.00010, 0.00551] IC 0.1069 [0.0970, 0.1168]; UP n=5710 sessions=1147 0.01371 [0.01060, 0.01684] IC 0.0653 [0.0578, 0.0725]
- dispersion: HIGH n=3560 sessions=706 0.01080 [0.00732, 0.01456] IC 0.0862 [0.0773, 0.0949]; LOW n=3391 sessions=707 0.00686 [0.00348, 0.01027] IC 0.0790 [0.0687, 0.0894]; MID n=3423 sessions=703 0.00848 [0.00415, 0.01287] IC 0.0881 [0.0774, 0.0988]
- fv002: BEARISH n=3686 sessions=722 0.01136 [0.00733, 0.01529] IC 0.0829 [0.0725, 0.0934]; BULLISH n=3908 sessions=816 0.00725 [0.00415, 0.01051] IC 0.0871 [0.0783, 0.0959]; NEUTRAL n=2780 sessions=578 0.00739 [0.00340, 0.01159] IC 0.0825 [0.0714, 0.0934]
- liquidity: HIGH n=2956 sessions=1553 0.00725 [0.00386, 0.01068] IC 0.0619 [0.0534, 0.0702]; LOW n=4088 sessions=1849 0.01027 [0.00669, 0.01387] IC 0.0965 [0.0900, 0.1029]; MID n=3330 sessions=1726 0.00821 [0.00511, 0.01139] IC 0.0840 [0.0773, 0.0908]
- volatility: HIGH n=3613 sessions=704 0.01145 [0.00747, 0.01540] IC 0.0889 [0.0777, 0.0995]; LOW n=3333 sessions=706 0.00698 [0.00378, 0.01024] IC 0.0835 [0.0751, 0.0921]; MID n=3428 sessions=706 0.00762 [0.00364, 0.01182] IC 0.0808 [0.0697, 0.0920]


## 10. Ranking metrics

### h = 3
- BASELINE-001: IC mean -0.0277 [-0.0334, -0.0219], median -0.0256, share > 0 0.386; Pearson -0.0170 [-0.0224, -0.0117]; top-bottom decile spread -0.00217 [-0.00305, -0.00133], share > 0 0.443 (2122 sessions)
- BASELINE-MEANREV: IC mean 0.0314 [0.0246, 0.0381], median 0.0282, share > 0 0.615; Pearson 0.0259 [0.0202, 0.0317]; top-bottom decile spread 0.00284 [0.00181, 0.00390], share > 0 0.552 (2122 sessions)
- BASELINE-MOM-20: IC mean -0.0206 [-0.0273, -0.0139], median -0.0181, share > 0 0.421; Pearson -0.0182 [-0.0231, -0.0134]; top-bottom decile spread -0.00184 [-0.00290, -0.00081], share > 0 0.485 (2122 sessions)
- BASELINE-MOM-5: IC mean -0.0390 [-0.0451, -0.0328], median -0.0377, share > 0 0.336; Pearson -0.0304 [-0.0356, -0.0252]; top-bottom decile spread -0.00382 [-0.00478, -0.00288], share > 0 0.404 (2122 sessions)
- BASELINE-RANDOM: IC mean 0.0024 [0.0009, 0.0039], median 0.0025, share > 0 0.534; Pearson 0.0019 [0.0004, 0.0034]; top-bottom decile spread 0.00018 [-0.00013, 0.00050], share > 0 0.516 (2122 sessions)
- BASELINE-RELATIVE: IC mean -0.0206 [-0.0273, -0.0139], median -0.0181, share > 0 0.421; Pearson -0.0182 [-0.0231, -0.0134]; top-bottom decile spread -0.00184 [-0.00290, -0.00081], share > 0 0.485 (2122 sessions)
- BASELINE-VOLADJ: IC mean -0.0149 [-0.0206, -0.0093], median -0.0125, share > 0 0.438; Pearson -0.0023 [-0.0078, 0.0031]; top-bottom decile spread -0.00088 [-0.00179, 0.00000], share > 0 0.493 (2122 sessions)
- SEL-001: IC mean 0.0901 [0.0849, 0.0953], median 0.0878, share > 0 0.872; Pearson 0.0824 [0.0776, 0.0872]; top-bottom decile spread 0.01122 [0.01044, 0.01202], share > 0 0.821 (2122 sessions)

### h = 5
- BASELINE-001: IC mean -0.0249 [-0.0318, -0.0179], median -0.0227, share > 0 0.389; Pearson -0.0133 [-0.0197, -0.0068]; top-bottom decile spread -0.00186 [-0.00322, -0.00050], share > 0 0.464 (2120 sessions)
- BASELINE-MEANREV: IC mean 0.0308 [0.0224, 0.0392], median 0.0264, share > 0 0.617; Pearson 0.0245 [0.0174, 0.0315]; top-bottom decile spread 0.00279 [0.00114, 0.00448], share > 0 0.522 (2120 sessions)
- BASELINE-MOM-20: IC mean -0.0210 [-0.0293, -0.0128], median -0.0176, share > 0 0.423; Pearson -0.0185 [-0.0247, -0.0122]; top-bottom decile spread -0.00175 [-0.00346, -0.00008], share > 0 0.495 (2120 sessions)
- BASELINE-MOM-5: IC mean -0.0387 [-0.0461, -0.0313], median -0.0355, share > 0 0.337; Pearson -0.0286 [-0.0346, -0.0225]; top-bottom decile spread -0.00427 [-0.00570, -0.00284], share > 0 0.418 (2120 sessions)
- BASELINE-RANDOM: IC mean 0.0022 [0.0007, 0.0036], median 0.0035, share > 0 0.539; Pearson 0.0023 [0.0008, 0.0037]; top-bottom decile spread 0.00049 [0.00007, 0.00091], share > 0 0.522 (2120 sessions)
- BASELINE-RELATIVE: IC mean -0.0210 [-0.0293, -0.0128], median -0.0176, share > 0 0.423; Pearson -0.0185 [-0.0247, -0.0122]; top-bottom decile spread -0.00175 [-0.00346, -0.00008], share > 0 0.495 (2120 sessions)
- BASELINE-VOLADJ: IC mean -0.0150 [-0.0219, -0.0078], median -0.0109, share > 0 0.442; Pearson -0.0003 [-0.0073, 0.0066]; top-bottom decile spread -0.00050 [-0.00201, 0.00095], share > 0 0.517 (2120 sessions)
- SEL-001: IC mean 0.0844 [0.0781, 0.0906], median 0.0829, share > 0 0.872; Pearson 0.0818 [0.0759, 0.0875]; top-bottom decile spread 0.01489 [0.01367, 0.01613], share > 0 0.831 (2120 sessions)


## 11. Limitations and possible confounders

- Sector is the current `Stock.sector` (also inside `rel_strength_sector_20d`); historical membership is not reconstructed.
- Survivorship: equity and rights status use the current `Stock` row, and stocks delisted before their bars were backfilled may be missing.
- BASELINE-001 is scored on split-adjusted bars; the live scan reads raw bars (SPG-001 §9.2).
- Regime cut points (volatility, dispersion terciles) use the whole walk-forward period and market direction uses the realised b(D,h): regimes describe, they are not tradeable signals.
- The label-permutation null keeps each session's target distribution and the fold structure; it does not preserve any stock-level time-series dependence.
- Many exploratory numbers are reported; only the predeclared rules E1-E6 classify the evidence.
- Cost is a flat 0.30% round trip with no slippage or market impact; the liquidity floor is Rs 1 crore.
- K=1 is reported only; at K=50 overlap suppression removes many candidates.
- Walk-forward months only (2018-01..2026-07). The held-out months are not part of SEL-002.

## 12. Whether the evidence supports proceeding to TG-001

Yes. The predeclared evidence recommends proceeding to TG-001. TG-001 is not resumed automatically on an A: the user decides from this report.

## 13. Reproducibility

- Code version: a4d4ebdf5e0d97930ebdc01054989a0526ed7cc9 (source digest src-sha256:8e385b9dc5b4622577f7c2612abc7f16e98d44cfa0536915ff8e2); SPG-001 frozen at 8944bf4, unchanged: True
- Dataset SHA-256 (SEL-DS-001, SPG pre-merge): {'3': 'ab07215f9790f19cb2d5efaf3a269db1a83d107290fb2ec895fcdf2cbe887eb0', '5': 'a2381ab89b873aa9e364fb03924b443d73bc68ca2a05542c66aab24db54c9b68'}; cache files {'dataset.pkl': '50035bae382cf525ece4643cfcf4cf876b9c61db3c4573110522ede73a6dc08e', 'wf_h3.npz': 'cda9efc995c1a71b8ee4dfe340357c0194f759f6c6ad822240c255a1bb898615', 'wf_h5.npz': '916390230c695ed0af5a896340d6470ed5532b01094ffa37ed6c9a3b5e426204'}; diagnostic frame {'3': '2cc719e5431db3405a7e116674500e564761465b5a2b940d04c012daa32b894c', '5': '1670289af3e9807d0f994c3fe911cc37074dcc64f8961fd74499fff6d8a85f1f'}
- Snapshot SHA-256 before 5f59b9bc929f5d0c3f8fcdada419d705f0e6e9a3c0e26efb72d79ae2f0579a8b, after 5f59b9bc929f5d0c3f8fcdada419d705f0e6e9a3c0e26efb72d79ae2f0579a8b
- Configuration fb882479825f02d73980182df44005a48600c9144581b35558401fb7c4a47f20 (SEL-002-CONFIG-001); evidence rules SEL-002-EVIDENCE-002, source SHA-256 9c0dea9e2f848334ab27b02643ebb3992b57726929a4f25fb556ebfbc2f08c57
- Seeds: bootstrap 42 (B=10000, L=h), random baseline 42 and (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20), permutations (1, 2, 3, 4, 5, 6, 7, 8), selection null 42 (1000 draws), SEL-001 random_state 42
- Validation windows: today 2026-10-01, first test month 2018-01, held-out month (2026, 8) from 2026-08-01, embargo 2 sessions, quarterly refits
- Universe: SEU-001: equity, not a rights entitlement, an official bar at D, >= 60 official bars, FV-001 required features at D, record contracts pass, median 20-session close x volume >= Rs 1 crore (10,000,000); label: D+1 official open to D+h official close, corporate-action adjusted; benchmark b(D,h): equal-weighted mean gross return of RESOLVED U(D) rows, benchmarkable with >= 100 of them
- Cost assumption: 0.003 round trip; horizons (3, 5); K (1, 5, 10, 25, 50)
- Libraries: {'python': '3.12.10', 'numpy': '2.5.2', 'pandas': '2.3.3', 'xgboost': '3.4.1'}; refit workers 2
- Full configuration snapshot: {'horizons': (3, 5), 'today': '2026-10-01', 'first_test_month': '2018-01', 'exam_start': '2026-08-01', 'expected_holdout_month': (2026, 8), 'k_values': (1, 5, 10, 25, 50), 'k_primary': 10, 'cost': 0.003, 'bootstrap_draws': 10000, 'bootstrap_seed': 42, 'random_seed': 42, 'random_distribution_seeds': (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20), 'permutation_seeds': (1, 2, 3, 4, 5, 6, 7, 8), 'selection_null_draws': 1000, 'selection_null_seed': 42, 'bucket_edges': (0, 1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100), 'top_fraction': 0.01, 'wide_fraction': 0.05, 'parity_tolerance': 1e-06, 'variants': ('PRICE-ONLY', 'VOLUME-ONLY', 'PRICE+VOLUME', 'LOGO-RET', 'LOGO-TREND', 'LOGO-OSC', 'LOGO-VOL', 'LOGO-RANGE', 'LOGO-VOLUME', 'LOGO-XSEC', 'LOGO-REGIME'), 'experiment': 'SEL-002', 'config_version': 'SEL-002-CONFIG-001', 'embargo_sessions': 2, 'sel001_params': {'n_estimators': 400, 'max_depth': 4, 'learning_rate': 0.05, 'subsample': 0.8, 'colsample_bytree': 0.8, 'min_child_weight': 100, 'reg_lambda': 1.0, 'objective': 'reg:squarederror', 'tree_method': 'hist', 'random_state': 42, 'n_jobs': 2}, 'feature_columns': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'atr_percent', 'momentum_rank_20d', 'volume_ratio_rank', 'rel_strength_market_20d', 'rel_strength_sector_20d', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'groups': {'RET': ['return_1d', 'return_5d', 'return_20d', 'return_60d'], 'TREND': ['sma20_distance', 'sma50_distance', 'macd', 'macd_signal', 'macd_hist'], 'OSC': ['rsi_14'], 'VOL': ['realized_vol_20d', 'realized_vol_60d', 'atr_percent'], 'RANGE': ['high_60d_distance', 'low_60d_distance'], 'VOLUME': ['volume_ratio_20d', 'volume_ratio_rank'], 'XSEC': ['momentum_rank_20d', 'rel_strength_market_20d', 'rel_strength_sector_20d'], 'REGIME': ['regime_bullish', 'regime_bearish', 'regime_neutral']}, 'variant_features': {'PRICE-ONLY': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'atr_percent'], 'VOLUME-ONLY': ['volume_ratio_20d', 'volume_ratio_rank'], 'PRICE+VOLUME': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'atr_percent', 'volume_ratio_rank'], 'LOGO-RET': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'atr_percent', 'momentum_rank_20d', 'volume_ratio_rank', 'rel_strength_market_20d', 'rel_strength_sector_20d', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'LOGO-TREND': ['rsi_14', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'atr_percent', 'momentum_rank_20d', 'volume_ratio_rank', 'rel_strength_market_20d', 'rel_strength_sector_20d', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'LOGO-OSC': ['sma20_distance', 'sma50_distance', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'atr_percent', 'momentum_rank_20d', 'volume_ratio_rank', 'rel_strength_market_20d', 'rel_strength_sector_20d', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'LOGO-VOL': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'momentum_rank_20d', 'volume_ratio_rank', 'rel_strength_market_20d', 'rel_strength_sector_20d', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'LOGO-RANGE': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'volume_ratio_20d', 'atr_percent', 'momentum_rank_20d', 'volume_ratio_rank', 'rel_strength_market_20d', 'rel_strength_sector_20d', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'LOGO-VOLUME': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'atr_percent', 'momentum_rank_20d', 'rel_strength_market_20d', 'rel_strength_sector_20d', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'LOGO-XSEC': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'atr_percent', 'volume_ratio_rank', 'regime_bullish', 'regime_bearish', 'regime_neutral'], 'LOGO-REGIME': ['sma20_distance', 'sma50_distance', 'rsi_14', 'macd', 'macd_signal', 'macd_hist', 'return_1d', 'return_5d', 'return_20d', 'return_60d', 'realized_vol_20d', 'realized_vol_60d', 'high_60d_distance', 'low_60d_distance', 'volume_ratio_20d', 'atr_percent', 'momentum_rank_20d', 'volume_ratio_rank', 'rel_strength_market_20d', 'rel_strength_sector_20d']}, 'regime_buckets': {'direction': ['UP', 'DOWN'], 'volatility': ['LOW', 'MID', 'HIGH'], 'dispersion': ['LOW', 'MID', 'HIGH'], 'liquidity': ['LOW', 'MID', 'HIGH'], 'fv002': ['BULLISH', 'BEARISH', 'NEUTRAL']}, 'frozen_settings': {'selection_top_k': 10, 'selection_round_trip_cost': 0.003, 'selection_bootstrap_draws': 10000, 'selection_bootstrap_seed': 42, 'selection_min_session_stocks': 100, 'selection_min_history_bars': 60, 'selection_min_median_traded_value_20d': 10000000.0, 'selection_min_benchmark_stocks': 100, 'selection_min_plausible_return': -0.6, 'selection_max_plausible_return': 1.5, 'selection_wf_first_test_month': '2018-01', 'selection_min_fit_rows': 100000}}
- Stage prepare: 200.226 s elapsed, 199.391 s CPU, peak 2224.2 MB
- Stage refit: 28749.028 s elapsed, 119169.952 s CPU, peak 1271.8 MB
- Stage parity: 7.725 s elapsed, 7.391 s CPU, peak 2224.2 MB
- Stage analyze_h3: 883.395 s elapsed, 881.281 s CPU, peak 3693.2 MB
- Stage analyze_h5: 844.06 s elapsed, 841.688 s CPU, peak 3697.5 MB
