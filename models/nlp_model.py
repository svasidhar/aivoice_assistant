from typing import Dict, List

class IntentClassifier:
    def __init__(self):
        self.intent_labels = {
            0: "restoration_query",
            1: "outage_report",
            2: "voltage_issue",
            3: "emergency",
            4: "critical_hazard"
        }

    def classify(self, text: str) -> str:
        """Classify intent based on text"""
        text_lower = text.lower()

        # Emergency keywords in both languages
        emergency_words = ["smoke", "fire", "spark", "wire down", "electric shock", "pole fell", 
                           "పొగ", "మంట", "నిప్పు", "తీగ", "వైరు", "షాక్", "స్తంభం", "నిప్పులు"]
        if any(word in text_lower for word in emergency_words):
            return "emergency"
        elif "voltage" in text_lower or "వోల్టేజ్" in text_lower or "వోల్టేజి" in text_lower:
            return "voltage_issue"
        elif any(word in text_lower for word in ["current", "light", "power", "కరెంట్", "కరెంటు", "పవర్", "లైట్"]):
            return "outage_report"
        elif any(word in text_lower for word in ["eppudu", "vastundi", "ఎప్పుడు", "వస్తుంది", "టైం", "time"]):
            return "restoration_query"
        else:
            return "unknown"

class EntityExtractor:
    def __init__(self):
        # Bilingual area mappings: Standardized English Name -> [List of English & Telugu triggers]
        self.area_mappings = {
            "Ramanapet": ["ramanapet", "ramanapeta", "ramanapet lo", "ramannapet", "రామన్నపేట", "రామన్నపేట్", "రమణపేట", "రామన్న పేట"],
            "Cherlapally": ["cherlapally", "cherlapally lo", "cherlapalli", "చర్లపల్లి", "చార్లపల్లి", "చర్లపల్లిలో"],
            "Siddipet": ["siddipet", "siddipet lo", "siddipeta", "సిద్దిపేట", "సిద్దిపేట్", "సిద్ది పేట"],
            "Narketpally": ["narketpally", "narketpalli", "narketpally lo", "narkatpally", "నార్కట్పల్లి", "నార్కట్‌పల్లి", "నార్కెట్‌పల్లి", "నార్కట్ పల్లి", "నార్కెట్ పల్లి"],
            "Choutuppal": ["choutuppal", "choutuppal lo", "చౌటుప్పల్", "చౌటుప్పల్ లో", "చౌటుప్పల్ ప్రదేశం"],
            "Nakrekal": ["nakrekal", "nakrekal lo", "నకిరేకల్", "నకిరేకల్ లో"],
            "Nizamabad": ["nizamabad", "nizamabad lo", "నిజామాబాద్", "నిజామాబాదు"],
            "Warangal": ["warangal", "warangal lo", "వరంగల్", "వరంగల్లు"],
            "Karimnagar": ["karimnagar", "karimnagar lo", "కరీంనగర్", "కరీంనగరు"],
            "Khammam": ["khammam", "khammam lo", "ఖమ్మం", "ఖమ్మము"],
            "Nalgonda": ["nalgonda", "nalgonda lo", "నల్గొండ", "నల్లగొండ"],
            "Suryapet": ["suryapet", "suryapet lo", "సూర్యాపేట", "సూర్యాపేట్"],
            "Mahabubnagar": ["mahabubnagar", "mahabubnagar lo", "మహబూబ్ నగర్", "మహబూబ్నగర్"]
        }

        # Bilingual issue mappings: Standardized Issue Name -> [List of English & Telugu triggers]
        self.issue_mappings = {
            "Power Outage": ["current", "light", "power", "outage", "కరెంట్", "కరెంటు", "పవర్", "లైట్", "లైట్లు లేవు"],
            "Voltage Issue": ["voltage", "fluctuation", "వోల్టేజ్", "వోల్టేజి", "లో వోల్టేజ్", "హై వోల్టేజ్"],
            "Transformer Problem": ["transformer", "ట్రాన్స్ ఫార్మర్", "ట్రాన్స్ఫార్మర్", "ట్రాన్స్ఫార్మరు", "ట్రాన్స్ఫార్మర్ పోయింది"],
            "Line Breakdown": ["breakdown", "break", "down", "బ్రేక్ డౌన్", "బ్రేక్డౌన్", "లైన్ తెగింది", "లైన్ బ్రేక్"]
        }

        # Bilingual ETA mappings: Standardized ETA Name -> [List of English & Telugu triggers]
        self.eta_mappings = {
            "30 minutes": ["30 nimishalu", "30 minutes", "muppai nimishalu", "30 నిమిషాలు", "ముప్పై నిమిషాలు", "30 min", "half hour"],
            "1 hour": ["one hour", "ganta", "1 hour", "ఒక గంట", "గంట", "1 ganta", "ganta padutundi"],
            "2 hours": ["two hours", "2 hours", "rendu gantalu", "రెండు గంటలు", "2 gantalu", "rendu"],
            "15 minutes": ["15 minutes", "padihenu nimishalu", "15 నిమిషాలు", "పదిహేను నిమిషాలు", "15 min", "పావు గంట"]
        }

    def extract_entities(self, text: str) -> Dict:
        entities = {}
        text_lower = text.lower()

        # Extract area by checking bilingual triggers
        for area, triggers in self.area_mappings.items():
            if any(trigger in text_lower for trigger in triggers):
                entities['area'] = area
                break
        
        # If no area matched, do not set a fake default. Set as "Unknown" to trigger recovery.
        if 'area' not in entities:
            entities['area'] = "Unknown"

        # Extract issue type by checking bilingual triggers
        for issue, triggers in self.issue_mappings.items():
            if any(trigger in text_lower for trigger in triggers):
                entities['issue'] = issue
                break
        
        if 'issue' not in entities:
            entities['issue'] = "Unknown"

        # Extract ETA dynamically by checking bilingual triggers
        for eta, triggers in self.eta_mappings.items():
            if any(trigger in text_lower for trigger in triggers):
                entities['eta'] = eta
                break
        
        if 'eta' not in entities:
            entities['eta'] = "Not Specified"
        return entities

class EmergencyDetector:
    def __init__(self):
        self.emergency_keywords = [
            "smoke", "fire", "spark", "wire down", "electric shock",
            "pole fell", "emergency", "voltage fluctuation",
            "పొగ", "మంట", "నిప్పు", "తీగ", "వైరు", "షాక్", "స్తంభం", 
            "నిప్పులు", "ప్రమాదం", "కరెంట్ షాక్"
        ]

    def detect_emergency(self, text: str) -> List[str]:
        """Detect emergency keywords in text"""
        text_lower = text.lower()
        emergencies = []
        for keyword in self.emergency_keywords:
            if keyword in text_lower:
                emergencies.append(keyword)
        return emergencies

class CustomNLPModel:
    def __init__(self):
        self.intent_classifier = IntentClassifier()
        self.entity_extractor = EntityExtractor()
        self.emergency_detector = EmergencyDetector()

    def process_text(self, text: str) -> Dict:
        """Process text and extract intent, entities and detect emergencies"""
        intent = self.intent_classifier.classify(text)
        entities = self.entity_extractor.extract_entities(text)
        is_emergency = len(self.emergency_detector.detect_emergency(text)) > 0

        return {
            "intent": intent,
            "entities": entities,
            "is_emergency": is_emergency,
            "text": text
        }