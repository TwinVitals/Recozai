ALTER TABLE membership_invitation ADD COLUMN issuer_membership_id uuid;
ALTER TABLE membership_invitation ADD COLUMN issuer_membership_version bigint;
ALTER TABLE membership_invitation ADD COLUMN issuer_credential_version bigint;
ALTER TABLE membership_invitation ADD FOREIGN KEY (clinic_id,issuer_membership_id)
    REFERENCES clinic_membership(clinic_id,id);
CREATE INDEX membership_invitation_issuer ON membership_invitation(clinic_id,issuer_membership_id);
