ALTER TABLE launcher_versions ADD CONSTRAINT launcher_target_relative CHECK (
  artifact_target ~ '^[A-Za-z0-9_-][A-Za-z0-9._/-]{0,255}$'
  AND artifact_target !~ '(^|/)[.]{1,2}(/|$)'
  AND artifact_target !~ '//' AND artifact_target NOT LIKE '%/');
