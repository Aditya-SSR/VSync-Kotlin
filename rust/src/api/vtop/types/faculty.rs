use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GetFaculty {
    pub faculty_name: String,
    pub designation: String,
    pub school_or_centre: String,
    pub emp_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FacultyDetails {
    pub name: String,
    pub designation: String,
    pub department: String,
    pub school_centre: String,
    pub email: String,
    pub cabin_number: String,
    pub office_hours: Vec<OfficeHour>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfficeHour {
    pub day: String,
    pub timings: String,
}
