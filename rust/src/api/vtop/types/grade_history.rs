use super::GradeCourseHistory;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GradeHistory {
    pub credits_registered: String,
    pub credits_earned: String,
    pub cgpa: String,
    pub courses: Vec<GradeCourseHistory>,
}
