package sn.recozai.identity.adapter;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="app_user")
public class UserEntity {
    @Id UUID id;
    @Column(name="normalized_login",nullable=false,length=120,unique=true) String normalizedLogin;
    @Column(name="password_hash",nullable=false,length=100) String passwordHash="";
    @Column(nullable=false,length=5) String locale="fr-SN";
    @Column(nullable=false) boolean enabled;
    @Column(name="credential_version",nullable=false) long credentialVersion;
    protected UserEntity() {}
    UserEntity(UUID id,String login) { this.id=id; this.normalizedLogin=login; }
}
