use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BiometricRecord {
    pub serial: String,
    pub date: String,
    pub day: String,
    pub in_time: String,
    pub out_time: String,
    pub duration: String,
    pub location: String,
}
