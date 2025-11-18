import matplotlib.pyplot as plt

# ---------------------------------------------
# DATASETS (filled using your extracted results)
# ---------------------------------------------

nodes = [50, 100, 150, 200, 250]

protocols = {
    "Spray and Wait": {
        "delivery": [0.7420, 0.8172, 0.8370, 0.8313, 0.8512],
        "overhead": [8.6182, 8.3213, 8.1989, 8.3127, 8.1371],
        "latency":  [1635.8450, 1471.0084, 1379.8245, 1386.5198, 1300.2382]
    },
    "EBR": {
        "delivery": [0.4833, 0.4833, 0.9247, 0.9588, 0.9754],
        "overhead": [28.7797, 28.7797, 108.7659, 152.3240, 190.8915],
        "latency":  [1965.3044, 1965.3044, 1412.5148, 1234.9857, 984.9251]
    },
    "DBRP": {
        "delivery": [0.1340, 0.1288, 0.1314, 0.1307, 0.1431],
        "overhead": [3.4084, 3.7397, 3.7211, 3.7731, 3.4466],
        "latency":  [909.9971, 828.8933, 930.9163, 946.1298, 960.2344]
    }
}

protocols_emrt = {
    "Spray and Wait-EMRT": {
        "delivery": [0.4560, 0.4405, 0.4514, 0.4444, 0.4648],
        "overhead": [2.9895, 2.8159, 2.7208, 2.7716, 2.6960],
        "latency":  [1652.1490, 1619.3518, 1617.1662, 1651.9722, 1526.1733]
    },
    "EBR-EMRT": {
        "delivery": [0.4519, 0.6296, 0.7034, 0.6612, 0.6560],
        "overhead": [22.6269, 50.6747, 84.7507, 107.9782, 117.8169],
        "latency":  [1985.7349, 1870.0158, 1520.2843, 1409.3169, 1306.2721]
    },
    "DBRP-EMRT": {
        "delivery": [0.1343, 0.1290, 0.1314, 0.1308, 0.1432],
        "overhead": [3.4040, 3.7354, 3.7221, 3.7690, 3.4429],
        "latency":  [909.9978, 830.6792, 932.0988, 948.8757, 959.2012]
    }
}

# ---------------------------------------------
# Helper function to plot each metric
# ---------------------------------------------

def plot_metric(metric_key, ylabel, title):
    plt.figure(figsize=(8, 5))

    color_map = {}

    # Base protocols (solid)
    for proto, metrics in protocols.items():
        line = plt.plot(nodes, metrics[metric_key], marker='o', label=proto)
        color_map[proto] = line[0].get_color()

    # EMRT (same color, dotted)
    for proto, metrics in protocols_emrt.items():
        base = proto.replace("-EMRT", "")
        plt.plot(nodes, metrics[metric_key], marker='o',
                 linestyle='dotted', color=color_map[base], label=proto)

    plt.title(title)
    plt.xlabel("Number of Nodes")
    plt.ylabel(ylabel)
    plt.grid(True)
    plt.legend()
    plt.tight_layout()
    plt.show()


# ---------------------------------------------
# Generate the 3 graphs
# ---------------------------------------------

plot_metric("delivery", "Delivery Ratio", "Nodes vs Delivery Ratio")
plot_metric("overhead", "Overhead Ratio", "Nodes vs Overhead Ratio")
plot_metric("latency", "Average Latency (ms)", "Nodes vs Average Latency")
