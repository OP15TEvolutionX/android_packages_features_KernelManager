# Upstream revisions

| Project | Revision | Sources used |
| --- | --- | --- |
| [Axion SDK](https://github.com/AxionAOSP/android_axion_sdk) | `2edaf6d6349ec12e9f53ee1cd45d9c8b0c62ae4c` (`lineage-23.2`) | `ax_km_common/src`, `ax_km_server/src` |
| [Axion framework](https://github.com/AxionAOSP/android_frameworks_base) | `df6ce26515b24bb42f5ad89f60e18b69804b81b3` (`lineage-23.2`) | Reference for Binder integration and `NodeCeiling` dependency |

## Evolution X adaptations

- Source filegroups replace the Axion SDK build layout.
- A new Settings Compose screen uses the existing Settings theme and resources;
  the Axion UI and unrelated SDK modules are not imported.
- Lifecycle starts after vendor boot setup. Hardware settings belong to the owner.
- Binder permission enforcement and a system-owner UID check protect all operations.
- Axion's PowerManager/Power HAL node-ceiling extension is replaced by verified
  standard sysfs writes for the QTI-based target, without a forced rewrite loop.
- Current values come from sysfs, not saved settings. Failures propagate to the UI.
- Writes validate enumerated values and preserve min/max ordering with rollback.
- Boot restoration is opt-in, saved governor names survive table reordering,
  defaults retain their raw sysfs representation (including devfreq zero bounds).
- Reset disables boot restoration and retains failed entries for a retry.
- Missing metric samples use an unavailable marker rather than a zero-load claim.

Keep AxionOS copyright headers when updating the imported sources. Compare loader,
metrics, parcelables and their Binder integration as one compatible revision set.
