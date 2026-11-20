use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeneralOutingRecord {
    pub serial: String,
    pub registration_number: String,
    pub place_of_visit: String,
    pub purpose_of_visit: String,
    pub from_date: String,
    pub from_time: String,
    pub to_date: String,
    pub to_time: String,
    pub status: String,
    pub can_download: bool,
    pub leave_id: String,
}
