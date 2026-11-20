use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MentorDetails {
    pub faculty_id: String,
    pub faculty_name: String,
    pub faculty_designation: String,
    pub school: String,
    pub cabin: String,
    pub faculty_department: String,
    pub faculty_email: String,
    pub faculty_intercom: String,
    pub faculty_mobile_number: String,
}
