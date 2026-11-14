import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { Api, Membership, Page, Role, roles, errorKey } from '../core/api.service';

@Component({
  imports: [ReactiveFormsModule, TranslatePipe],
  template: ` <section class="card">
    <h1>{{ 'members' | translate }}</h1>
    @if (api.admin()) {
      <form [formGroup]="invitation" (ngSubmit)="invite()">
        <label
          >{{ 'loginName' | translate }}<input formControlName="login" maxlength="120"
        /></label>
        <label
          >{{ 'rolesLabel' | translate
          }}<select multiple formControlName="roles">
            @for (role of roles; track role) {
              <option [value]="role">{{ 'roles.' + role | translate }}</option>
            }
          </select></label
        ><button [disabled]="busy()">{{ 'invite' | translate }}</button>
      </form>
      @if (issued(); as code) {
        <aside class="notice">
          <p>{{ 'invitationHelp' | translate }}</p>
          <label>{{ 'invitationId' | translate }}<input readonly [value]="code.id" /></label>
          <label
            >{{ 'token' | translate }}<input readonly [value]="code.token" autocomplete="off"
          /></label>
        </aside>
      }
      @if (loading()) {
        <p role="status">{{ 'loading' | translate }}</p>
      }
      @for (member of members(); track member.id) {
        <article class="member">
          <h2>{{ member.login }}</h2>
          @if (controls.get(member.id); as control) {
            <label
              >{{ 'rolesLabel' | translate
              }}<select multiple [formControl]="control">
                @for (role of roles; track role) {
                  <option [value]="role">{{ 'roles.' + role | translate }}</option>
                }
              </select></label
            >
            <button
              [disabled]="busy() || member.id === api.user()?.membershipId"
              (click)="update(member, member.active)"
            >
              {{ 'save' | translate }}
            </button>
            <button
              class="secondary"
              [disabled]="busy() || member.id === api.user()?.membershipId"
              (click)="update(member, !member.active)"
            >
              {{ (member.active ? 'disable' : 'enable') | translate }}
            </button>
            <button class="secondary" [disabled]="busy()" (click)="revoke(member)">
              {{ 'revoke' | translate }}
            </button>
          }
        </article>
      } @empty {
        @if (!loading()) {
          <p>{{ 'empty' | translate }}</p>
        }
      }
      <div class="actions">
        <button [disabled]="page() === 0 || loading()" (click)="load(page() - 1)">
          {{ 'previous' | translate }}
        </button>
        <button [disabled]="members().length < 25 || loading()" (click)="load(page() + 1)">
          {{ 'next' | translate }}
        </button>
      </div>
    } @else {
      <p role="alert">{{ 'errors.FORBIDDEN' | translate }}</p>
    }
    @if (error()) {
      <p class="error" role="alert">{{ error() | translate }}</p>
    }
  </section>`,
})
export class Members {
  readonly api = inject(Api);
  readonly roles = roles;
  readonly members = signal<Membership[]>([]);
  readonly controls = new Map<string, FormControl<Role[]>>();
  readonly invitation = inject(FormBuilder).nonNullable.group({
    login: ['', Validators.required],
    roles: new FormControl<Role[]>(['BILLING_STAFF'], {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });
  readonly issued = signal<{ id: string; token: string } | null>(null);
  readonly page = signal(0);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  private readonly destroy = inject(DestroyRef);
  constructor() {
    if (this.api.admin()) this.load(0);
  }
  load(page: number) {
    this.loading.set(true);
    this.error.set('');
    this.api
      .get<Page<Membership>>(`/memberships?page=${page}`)
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (result) => {
          const members = result.items;
          this.page.set(page);
          this.members.set(members);
          this.controls.clear();
          for (const member of members)
            this.controls.set(member.id, new FormControl(member.roles, { nonNullable: true }));
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }
  invite() {
    if (this.invitation.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.issued.set(null);
    this.api
      .mutate<{ id: string; token: string }>(
        'POST',
        '/membership-invitations',
        this.invitation.getRawValue(),
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (result) => this.issued.set(result),
        error: (error) => this.error.set(errorKey(error)),
      });
  }
  update(member: Membership, active: boolean) {
    const selected = this.controls.get(member.id)?.value;
    if (!selected?.length) {
      this.error.set('errors.ROLES_REQUIRED');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.api
      .mutate<void>(
        'PATCH',
        `/memberships/${member.id}`,
        { active, roles: selected },
        member.version,
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => this.load(this.page()),
        error: (error) => this.error.set(errorKey(error)),
      });
  }
  revoke(member: Membership) {
    this.busy.set(true);
    this.error.set('');
    this.api
      .mutate<void>('POST', `/memberships/${member.id}/session-revocations`)
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => this.load(this.page()),
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
