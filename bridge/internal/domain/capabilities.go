package domain

type CapabilityReport struct {
	ExactAlarm    string `json:"exact_alarm"`
	Notifications string `json:"notifications"`
	OfflineVoice  string `json:"offline_voice"`
	Storage       string `json:"storage"`
	ObservedAt    string `json:"observed_at"`
}
