"""Same spherical geodesic calculation and decision order as Kotlin GpsRule.

The sum of margins is operational, not a statistical confidence interval.
"""
import math

def distance(lat1, lon1, lat2, lon2, radius=6371008.8):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2-p1)/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(math.radians(lon2-lon1)/2)**2
    return 2*radius*math.asin(math.sqrt(min(1, max(0, h))))

def decide(d, accuracy, age, uncertainty, permission, mock, ambiguous, error, p):
    reasons = []
    if d is None or error or age is None or age < 0 or age > p["max_age_s"]:
        return {"state": "NON_DISPONIBILE", "distance_m": d, "reasons": [error or "misura assente o troppo vecchia"]}
    if permission != "PRECISE": reasons.append("permesso non preciso")
    if accuracy is None or accuracy < 0 or accuracy > p["max_accuracy_m"]: reasons.append("accuratezza insufficiente")
    if uncertainty is None: reasons.append("qualità cartografica da verificare")
    elif uncertainty < 0 or uncertainty > p["max_map_uncertainty_m"]: reasons.append("incertezza cartografica elevata")
    if mock is True: reasons.append("posizione simulata segnalata")
    if ambiguous: reasons.append("manufatti vicini: selezione da verificare")
    if reasons: return {"state": "INCERTA", "distance_m": d, "reasons": reasons}
    if d + accuracy + uncertainty <= p["radius_m"]: state = "COMPATIBILE"
    elif d - accuracy - uncertainty > p["radius_m"]: state = "NON_COMPATIBILE"
    else: state = "INCERTA"
    return {"state": state, "distance_m": d, "reasons": [] if state == "COMPATIBILE" else ["margine di prossimità"]}

def evaluate(event, point, points, p):
    lat, lon = event.get("latitude"), event.get("longitude")
    d = None if lat is None or lon is None else distance(lat, lon, point["latitude"], point["longitude"], p["earth_radius_m"])
    # Nearby candidates use a documented fixed rule, never the most favourable observation.
    ambiguous = d is not None and sum(distance(lat, lon, x["latitude"], x["longitude"], p["earth_radius_m"]) <= p["radius_m"] for x in points) > 1
    result = decide(d, event.get("accuracy_m"), event.get("age_s"), point.get("uncertainty_m"), event["permission"], event.get("mock"), ambiguous, event.get("error"), p)
    return {**result, "rule_version": p["version"], "parameters": p, "ambiguous": ambiguous}
