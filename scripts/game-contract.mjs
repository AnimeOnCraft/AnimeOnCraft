// Reviewed friends admission contract. Does not contain server keys or player tickets.
import { readFileSync, writeFileSync } from 'node:fs';
const ref = name => ({ $ref: `#/components/schemas/${name}` });
const object = properties => ({ type: 'object', additionalProperties: false, required: Object.keys(properties), properties });
const gameName = { type: 'string', minLength: 3, maxLength: 16, pattern: '^[A-Za-z0-9_]{3,16}(?![\\s\\S])' };
const uuid = { type: 'string', format: 'uuid' };
const uuidVersion = version => ({ ...uuid, pattern: `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-${version}[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}(?![\\s\\S])` });
const ticket = { type: 'string', pattern: '^gt_[A-Za-z0-9_-]{43}(?![\\s\\S])' };
const schemas = {
  TicketInput: object({}),
  Ticket: object({ ticket, server_id: uuid, game_name: gameName, minecraft_uuid: uuidVersion(3), expires_at: { type: 'string', format: 'date-time' } }),
  RedeemInput: object({ ticket, game_name: gameName, minecraft_uuid: uuidVersion(3), connection_id: uuidVersion(4) }),
  Admission: object({ accepted: { const: true }, connection_id: uuidVersion(4), user_id: uuid, game_name: gameName, minecraft_uuid: uuidVersion(3), clan: { anyOf: [object({ id: uuid, short_code: { type: 'string', maxLength: 12 } }), { type: 'null' }] } }),
  ErrorResponse: JSON.parse(readFileSync('packages/contracts/openapi/auth.yaml', 'utf8')).components.schemas.ErrorResponse
};
const errors = Object.fromEntries(['400','401','403','429','503'].map(status => [status, { description: 'Request rejected or dependency unavailable. No admission granted.', content: { 'application/json': { schema: ref('ErrorResponse') } } }]));
function post(operationId, input, output, security, description) {
  return { operationId, description, security: [{ [security]: [] }],
    requestBody: { required: input !== 'TicketInput', content: { 'application/json': { schema: ref(input) } } },
    responses: { 200: { description: 'Successful response; Cache-Control: no-store.', content: { 'application/json': { schema: ref(output) } } }, ...errors } };
}
const document = {
  openapi: '3.1.0',
  info: { title: 'AnimeOnCraft Friends Game Admission Development API', version: '0.7.20-friends-dev', description: 'Backend foundation only. NeoForge handshake and launcher PLAY are not implemented. One configured server, 60-second opaque single-use tickets; no Microsoft binding required.' },
  servers: [{ url: 'http://127.0.0.1:3000/api/v1/game' }],
  paths: {
    '/tickets': { post: { ...post('issueGameTicket', 'TicketInput', 'Ticket', 'NativeAccess', 'Launcher only. Requires X-AOC-Client: launcher, no browser origin/cookies, current session, game profile, active grant and no ban. The server is selected by backend configuration, not by input. New issue replaces any pending ticket for this account/server.'), parameters: [{ in: 'header', name: 'X-AOC-Client', required: true, schema: { const: 'launcher' } }] } },
    '/admission/redeem': { post: post('redeemGameTicket', 'RedeemInput', 'Admission', 'GameServerKey', 'Trusted server only. No browser transport. Validates exact game name/UUID, configured server and current account/session/grant/ban/maintenance state again. Consumes atomically; retries/replays fail. Echoes the server-supplied connection ID. Never log the ticket or server credential.') }
  },
  components: {
    securitySchemes: {
      NativeAccess: { type: 'http', scheme: 'bearer', description: 'Ordinary launcher access token; cannot redeem a ticket.' },
      GameServerKey: { type: 'http', scheme: 'bearer', description: 'Private 64-hex server credential AOC_GAME_SERVER_KEY. Never distribute in client/launcher/configuration exposed to players.' }
    }, schemas
  }
};
writeFileSync('packages/contracts/openapi/game.yaml', JSON.stringify(document, null, 2) + '\n');
