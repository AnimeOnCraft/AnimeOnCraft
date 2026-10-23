import { randomUUID } from 'node:crypto';
import type { AuthService } from './auth.service.js';

export interface OwnerCommand { action: 'bootstrap'|'transfer'|'recover-mfa'; user_id: string; password: string; proof: string; target_id?: string; target_password?: string; target_proof?: string }
export async function ownerCommand(auth: AuthService, command: OwnerCommand): Promise<void> {
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
  if (!uuid.test(command.user_id) || (command.action === 'transfer' && (!command.target_id || !uuid.test(command.target_id) || command.user_id === command.target_id))) throw new Error('Invalid operator command');
  const initial = await auth.db.user.findUnique({ where: { id: command.user_id } });
  if (!initial || !(await auth.crypto.verify(initial.passwordHash, command.password))) throw new Error('Operator proof rejected');
  const target = command.action === 'transfer' ? await auth.db.user.findUnique({ where: { id: command.target_id! } }) : null;
  if (command.action === 'transfer' && (!target || !(await auth.crypto.verify(target.passwordHash, command.target_password ?? '')))) throw new Error('Operator target proof rejected');
  await auth.db.$transaction(async tx => {
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(171733)`;
    const ids = [initial.id, ...(target ? [target.id] : [])].sort();
    for (const id of ids) await auth.lockUser(tx, id);
    const user = await tx.user.findUniqueOrThrow({ where: { id: initial.id } });
    if (user.passwordHash !== initial.passwordHash || user.state !== 'ACTIVE' || user.passwordChangeRequired || !(await auth.usable(tx, user))) throw new Error('Operator proof rejected');
    if (command.action === 'bootstrap') {
      if (await tx.user.count({ where: { systemRole: 'OWNER' } })) throw new Error('OWNER already exists');
    } else if (user.systemRole !== 'OWNER') throw new Error('Existing OWNER required');
    if (command.action === 'recover-mfa' && !command.proof.startsWith('rc_')) throw new Error('One-time recovery code required');
    const credential=await tx.mfa_credentials.findUnique({where:{user_id:user.id}});
    // Local first-owner setup must precede OWNER-only MFA UI. Remote admin still requires MFA.
    if ((command.action!=='bootstrap'||credential?.enabled) && !(await auth.mfaProof(tx, user.id, command.proof))) throw new Error('Operator MFA proof rejected');
    if (target) {
      const current = await tx.user.findUniqueOrThrow({ where: { id: target.id } });
      if (current.passwordHash !== target.passwordHash || current.state !== 'ACTIVE' || !(await auth.usable(tx, current)) || !(await auth.mfaProof(tx, current.id, command.target_proof ?? ''))) throw new Error('Target MFA proof rejected');
      await tx.user.update({ where: { id: user.id }, data: { systemRole: 'PLAYER', authEpoch: { increment: 1 }, version: { increment: 1 } } });
      await tx.user.update({ where: { id: current.id }, data: { systemRole: 'OWNER', authEpoch: { increment: 1 }, version: { increment: 1 } } });
      await auth.revoke(tx, current.id);
    } else if (command.action === 'bootstrap') {
      await tx.user.update({ where: { id: user.id }, data: { systemRole: 'OWNER', authEpoch: { increment: 1 }, version: { increment: 1 } } });
    } else {
      await tx.mfa_credentials.update({ where: { user_id: user.id }, data: { enabled: false, pending_expires_at: new Date(Date.now() + 5 * 60000), last_used_step: -1n } });
      await tx.mfa_recovery_codes.updateMany({ where: { user_id: user.id, used_at: null }, data: { used_at: new Date() } });
      await tx.user.update({ where: { id: user.id }, data: { authEpoch: { increment: 1 }, version: { increment: 1 } } });
    }
    await auth.revoke(tx, user.id);
    await tx.audit_logs.create({ data: { actor_kind: 'OPERATOR', action: `OWNER_${command.action.toUpperCase().replaceAll('-','_')}`, target_type: 'users', target_id: target?.id ?? user.id,
      before_redacted: { previous_owner_id: command.action === 'bootstrap' ? null : user.id }, after_redacted: { owner_id: target?.id ?? user.id },
      reason: command.action==='bootstrap'&&!credential?.enabled?'Local first-owner setup with existing password':'Local operator command with password and one-time MFA proof', request_id: randomUUID() } });
  }, { timeout: 5000, maxWait: 2000 });
}
