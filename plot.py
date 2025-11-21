import os
import re
import math
import matplotlib.pyplot as plt

REPORTS_DIR = "reports/"

# ------------------------------------------------------------
# FLEXIBLE FLOAT REGEX: matches numbers, scientific form, NaN
# ------------------------------------------------------------
float_pattern = r"([-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?|NaN|nan|INF|inf)"

delivery_re = re.compile(rf"delivery_prob\s*:\s*{float_pattern}", re.IGNORECASE)
overhead_re = re.compile(rf"overhead_ratio\s*:\s*{float_pattern}", re.IGNORECASE)
latency_re = re.compile(rf"latency_avg\s*:\s*{float_pattern}", re.IGNORECASE)

protocols = {}

# --------------------------------------------------
# SAFE FLOAT CONVERSION
# --------------------------------------------------
def to_float(x):
    try:
        v = float(x)
        if math.isnan(v):
            return None
        return v
    except:
        return None


# --------------------------------------------------
# STEP 1 — Parse all MessageStatsReport files only
# --------------------------------------------------
for filename in os.listdir(REPORTS_DIR):
    if not filename.endswith("MessageStatsReport.txt"):
        continue

    parts = filename.split("_")

    if len(parts) < 3:
        print("Skipping bad filename:", filename)
        continue

    if not parts[1].isdigit():
        print("Skipping invalid file (node count not numeric):", filename)
        continue

    protocol_name = parts[0]
    node_count = int(parts[1])

    if protocol_name not in protocols:
        protocols[protocol_name] = {}

    content = open(os.path.join(REPORTS_DIR, filename)).read()

    d = delivery_re.search(content)
    o = overhead_re.search(content)
    l = latency_re.search(content)

    if not d or not o or not l:
        print("Skipping file (missing fields):", filename)
        continue

    delivery = to_float(d.group(1))
    overhead = to_float(o.group(1))
    latency = to_float(l.group(1))

    if delivery is None or overhead is None or latency is None:
        print("Skipping file (invalid numeric data):", filename)
        continue

    protocols[protocol_name][node_count] = {
        "delivery": delivery,
        "overhead": overhead,
        "latency": latency
    }


# --------------------------------------------------
# STEP 2 — Classify protocols (Base vs EMRT)
# --------------------------------------------------
base_protocols = []
emrt_protocols = []

for name in protocols.keys():
    if "EMRT" in name.upper():
        emrt_protocols.append(name)
    else:
        base_protocols.append(name)

base_protocols.sort()
emrt_protocols.sort()


# --------------------------------------------------
# STEP 3 — Plotting helper
# --------------------------------------------------
def plot_metric(metric, ylabel, title):
    plt.figure(figsize=(8, 5))

    color_map = {}  # base → color

    # ------------ BASE PROTOCOLS ------------
    for proto in base_protocols:
        data = protocols.get(proto, {})
        nodes_sorted = sorted(data.keys())

        if len(nodes_sorted) == 0:
            print("Skipping base proto (no data):", proto)
            continue

        values = [data[n][metric] for n in nodes_sorted]

        line = plt.plot(
            nodes_sorted,
            values,
            marker='o',
            markersize=7,
            label=proto,
            alpha=0.90,             # slightly transparent
            linewidth=2,
        )

        color_map[proto] = line[0].get_color()

    # ------------ EMRT PROTOCOLS ------------
    for proto in emrt_protocols:
        data = protocols.get(proto, {})
        nodes_sorted = sorted(data.keys())

        if len(nodes_sorted) == 0:
            print("Skipping emrt proto (no data):", proto)
            continue

        # match EMRT to correct base by removing "EMRT"
        match = None
        reduced = proto.lower().replace("emrt", "")
        for base in base_protocols:
            if base.lower() in reduced:
                match = base
                break

        color = color_map.get(match, None)

        values = [data[n][metric] for n in nodes_sorted]

        plt.plot(
            nodes_sorted,
            values,
            marker='o',
            markersize=7,
            linestyle='dotted',
            label=proto,
            color=color,
            alpha=0.50,          # more transparent for EMRT
            linewidth=2,
        )

    # ---------------------------------------
    plt.title(title)
    plt.xlabel("Number of Nodes")
    plt.ylabel(ylabel)
    plt.grid(True)
    plt.legend()
    plt.tight_layout()
    plt.show()


# --------------------------------------------------
# STEP 4 — Generate plots
# --------------------------------------------------
plot_metric("delivery", "Delivery Ratio", "Nodes vs Delivery Ratio")
plot_metric("overhead", "Overhead Ratio", "Nodes vs Overhead Ratio")
plot_metric("latency", "Average Latency (ms)", "Nodes vs Average Latency")
# --------------------------------------------------
# STEP 5 — Derived Metric Plots
# --------------------------------------------------

def plot_derived(metric_name, ylabel, title):
    plt.figure(figsize=(8, 5))
    color_map = {}

    # ------------ BASE PROTOCOLS ------------
    for proto in base_protocols:
        data = protocols.get(proto, {})
        nodes_sorted = sorted(data.keys())

        if len(nodes_sorted) == 0:
            continue

        # Compute derived metric
        values = []
        for n in nodes_sorted:
            d = data[n]["delivery"]
            o = data[n]["overhead"]
            l = data[n]["latency"]

            if metric_name == "d_by_l":
                values.append(d * (1/l))
            elif metric_name == "d_by_o":
                values.append(d * (1/o))
            elif metric_name == "d_by_l_o":
                values.append(d * (1/l) * (1/o))

        line = plt.plot(
            nodes_sorted,
            values,
            marker='o',
            markersize=7,
            label=proto,
            alpha=0.90,
            linewidth=2,
        )

        color_map[proto] = line[0].get_color()

    # ------------ EMRT PROTOCOLS ------------
    for proto in emrt_protocols:
        data = protocols.get(proto, {})
        nodes_sorted = sorted(data.keys())

        if len(nodes_sorted) == 0:
            continue

        match = None
        reduced = proto.lower().replace("emrt", "")
        for b in base_protocols:
            if b.lower() in reduced:
                match = b
                break

        color = color_map.get(match, None)

        values = []
        for n in nodes_sorted:
            d = data[n]["delivery"]
            o = data[n]["overhead"]
            l = data[n]["latency"]

            if metric_name == "d_by_l":
                values.append(d * (1/l))
            elif metric_name == "d_by_o":
                values.append(d * (1/o))
            elif metric_name == "d_by_l_o":
                values.append(d * (1/l) * (1/o))

        plt.plot(
            nodes_sorted,
            values,
            marker='o',
            markersize=7,
            linestyle='dotted',
            label=proto,
            color=color,
            alpha=0.55,
            linewidth=2,
        )

    plt.title(title)
    plt.xlabel("Number of Nodes")
    plt.ylabel(ylabel)
    plt.grid(True)
    plt.legend()
    plt.tight_layout()
    plt.show()


# --------------------------------------------------
# STEP 6 — Generate Derived Metric Graphs
# --------------------------------------------------

# 4) Delivery ratio × (1 / latency)
plot_derived(
    "d_by_l",
    "Delivery × (1/Latency)",
    "Nodes vs Delivery Ratio × (1 / Latency Avg)"
)

# 5) Delivery ratio × (1 / overhead)
plot_derived(
    "d_by_o",
    "Delivery × (1/Overhead)",
    "Nodes vs Delivery Ratio × (1 / Overhead Ratio)"
)

# 6) Delivery ratio × (1 / latency) × (1 / overhead)
plot_derived(
    "d_by_l_o",
    "Delivery × (1/Latency) × (1/Overhead)",
    "Nodes vs Delivery × (1/Latency) × (1/Overhead)"
)
