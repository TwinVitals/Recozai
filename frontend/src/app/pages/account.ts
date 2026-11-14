import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { Api, errorKey } from '../core/api.service';

@Component({
  imports: [ReactiveFormsModule, TranslatePipe],
  template: ` <section class="card narrow">
    <h1>{{ 'account' | translate }}</h1>
    <p>{{ 'accessHelp' | translate }}</p>
    @if (api.user()) {
      <form [formGroup]="own" (ngSubmit)="changePassword()">
        <label
          >{{ 'currentPassword' | translate
          }}<input
            type="password"
            formControlName="currentPassword"
            autocomplete="current-password"
        /></label>
        <label
          >{{ 'newPassword' | translate
          }}<input
            type="password"
            formControlName="newPassword"
            autocomplete="new-password"
            maxlength="72"
        /></label>
        <button [disabled]="busy()">{{ 'changePassword' | translate }}</button>
        <button type="button" class="secondary" [disabled]="busy()" (click)="revoke()">
          {{ 'revokeOwn' | translate }}
        </button>
      </form>
    } @else {
      <form [formGroup]="form" (ngSubmit)="complete()">
        <label
          >{{ 'action' | translate
          }}<select formControlName="action">
            <option value="account-activations">{{ 'activation' | translate }}</option>
            <option value="password-resets">{{ 'recovery' | translate }}</option>
          </select></label
        >
        <label
          >{{ 'token' | translate
          }}<input formControlName="token" autocomplete="off" maxlength="100"
        /></label>
        <label
          >{{ 'newPassword' | translate
          }}<input
            type="password"
            formControlName="password"
            autocomplete="new-password"
            maxlength="72"
        /></label>
        <button [disabled]="busy()">{{ 'submit' | translate }}</button>
      </form>
    }
    @if (message()) {
      <p role="status">{{ message() | translate }}</p>
    }
    @if (error()) {
      <p class="error" role="alert">{{ error() | translate }}</p>
    }
  </section>`,
})
export class Account {
  readonly api = inject(Api);
  readonly form = inject(FormBuilder).nonNullable.group({
    action: ['account-activations'],
    token: ['', Validators.required],
    password: ['', [Validators.required, Validators.minLength(12)]],
  });
  readonly own = inject(FormBuilder).nonNullable.group({
    currentPassword: ['', Validators.required],
    newPassword: ['', [Validators.required, Validators.minLength(12)]],
  });
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  private readonly destroy = inject(DestroyRef);
  complete() {
    if (this.form.invalid) {
      this.error.set('validation');
      return;
    }
    const value = this.form.getRawValue();
    this.perform(`/${value.action}`, { token: value.token, password: value.password }, false);
  }
  changePassword() {
    if (this.own.invalid) {
      this.error.set('validation');
      return;
    }
    this.perform('/me/password-changes', this.own.getRawValue(), true);
  }
  revoke() {
    if (this.own.controls.currentPassword.invalid) {
      this.error.set('validation');
      return;
    }
    this.perform(
      '/me/session-revocations',
      { password: this.own.controls.currentPassword.value },
      true,
    );
  }
  private perform(path: string, body: object, revokes: boolean) {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<void>('POST', path, body)
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => {
          this.form.reset();
          this.own.reset();
          if (revokes) this.api.user.set(null);
          this.message.set(revokes ? 'passwordChanged' : 'completed');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
