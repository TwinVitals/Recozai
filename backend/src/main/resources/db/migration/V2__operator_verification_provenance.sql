ALTER TABLE operator_authorization ADD COLUMN custodian varchar(120);
ALTER TABLE operator_authorization ADD COLUMN verification_reference varchar(160);
ALTER TABLE operator_authorization ADD COLUMN action varchar(20) CHECK (action IN ('BOOTSTRAP','ENROLL','RECOVER'));
ALTER TABLE operator_authorization ADD COLUMN security_epoch varchar(120);
ALTER TABLE operator_authorization ADD COLUMN provenance_status varchar(20) NOT NULL DEFAULT 'LEGACY_UNVERIFIED'
    CHECK (provenance_status IN ('LEGACY_UNVERIFIED','VERIFIED'));
ALTER TABLE operator_authorization ADD CHECK (provenance_status='LEGACY_UNVERIFIED' OR
    (custodian IS NOT NULL AND verification_reference IS NOT NULL AND action IS NOT NULL AND security_epoch IS NOT NULL));
CREATE TRIGGER immutable_operator_authorization BEFORE UPDATE OR DELETE ON operator_authorization
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
