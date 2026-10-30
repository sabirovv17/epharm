# Fresh native projection rehearsal — 30 October 2026

The migration candidate described in
[fresh-database-preservation-087.md](fresh-database-preservation-087.md) also passed the existing
owned projection pipeline. Aggregate evidence is in
[fresh-owned-pipeline-087.json](fresh-owned-pipeline-087.json); complete source records, mappings,
logs and per-operation reports remain outside Git.

The identity map was verified, all five writers completed, and all five repeated runs returned
`verified_existing_noop`:

1. `build_identity_map` rebuilt the map from the fresh typed artifact, after every relevant ID,
   crosswalk, dependency and FK matched the previously reviewed provenance.
2. `owned_merch` preserved organizations, branches, inventory, placements, source account/customer
   records and explicit source links.
3. `owned_tasks` preserved task packets/items, checklists, attachments, events and delivery history.
4. `owned_delivery` projected the explicit source parent/link ownership metadata without issuing a
   link or replaying an acknowledgement.
5. `owned_automation` preserved source cycles and source MAS membership without inventing an
   administrative account or verified person.
6. `owned_extended` preserved commercial settings/budgets/product snapshots, explicit historical
   request references and floor-plan/traffic projections.

The map retains all 680 CRM branch UUIDs: 532 explicit pharmacy relations, including the original
530 staged relations and two separately proven supplements; 148 relations remain absent.
It also retains 5,080 containers, 16,914 shelves, 16,935 monthly inventory records, six source
accounts, 32 membership scopes and the original access-code/scope IDs. Identity resolution creates
no new permissions or human identity.

After the complete first/repeated pipeline, all 72 original ePharm business tables still contain exactly
1,932,894 rows in total, with unchanged original column definitions, full PK definitions, PK value
hashes and original-column row hashes. The fresh source artifacts were not modified.

The three unbound source placements are retained as the user-confirmed historical test exception.
They refer to one source customer profile with a blank company name; neither a company name nor a
producer binding was guessed. Source/staging rows were not deleted. These test records alone are
not a requirement to invent a business owner.

The resulting candidate has zero canonical people, administrative access profiles, learner
credential copies, new task attempts, task rewards or native-generated task packets. Source
attachments remain private legacy references; their verified filesystem copy and the final
runtime-file checks are separate evidence. No application, worker, API replay, SMS, source database
write or production operation was executed. This successful offline preservation/projection
rehearsal does not by itself close functional, external-key recovery, deployment or cutover gates.
