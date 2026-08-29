import SwiftUI

private struct PendingUnraidAction: Identifiable {
    let action: UnraidControlledAction
    let targetId: String?
    let targetLabel: String

    var id: String { "\(action.rawValue)-\(targetId ?? "global")" }
}

struct UnraidDashboard: View {
    let instanceId: UUID

    @Environment(ServicesStore.self) private var servicesStore
    @Environment(Localizer.self) private var localizer

    @State private var selectedInstanceId: UUID
    @State private var overview: UnraidOverview?
    @State private var state: LoadableState<Void> = .idle
    @State private var busyTarget: String?
    @State private var actionErrorMessage: String?
    @State private var pendingAction: PendingUnraidAction?

    private let accentColor = ServiceType.unraid.colors.primary
    private let actionGrid = [GridItem(.adaptive(minimum: 120), spacing: 8)]

    init(instanceId: UUID) {
        self.instanceId = instanceId
        _selectedInstanceId = State(initialValue: instanceId)
    }

    var body: some View {
        ServiceDashboardLayout(
            serviceType: .unraid,
            instanceId: selectedInstanceId,
            state: state,
            onRefresh: fetchDashboard
        ) {
            instancePicker

            if let overview {
                if let info = overview.info {
                    systemCard(info)
                }
                if let array = overview.array {
                    arrayCard(array)
                    diskSection(localizer.t.unraidParityDisks, disks: array.parities ?? [])
                    diskSection(localizer.t.unraidDataDisks, disks: array.disks ?? [])
                    diskSection(localizer.t.unraidCacheDisks, disks: array.caches ?? [])
                }
                containerSection(overview)
                vmSection(overview)
                shareSection(overview)
                notificationSection(overview)
                unavailableSectionsCard(overview)
            }
        }
        .navigationTitle(ServiceType.unraid.displayName)
        .task(id: selectedInstanceId) {
            await fetchDashboard()
        }
        .confirmationDialog(
            localizer.t.actionConfirm,
            isPresented: Binding(
                get: { pendingAction != nil },
                set: { if !$0 { pendingAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let pending = pendingAction {
                Button(
                    label(for: pending.action),
                    role: pending.action.isDestructive ? .destructive : nil
                ) {
                    pendingAction = nil
                    Task { await perform(pending.action, targetId: pending.targetId, confirmed: true) }
                }
            }
            Button(localizer.t.cancel, role: .cancel) { pendingAction = nil }
        } message: {
            Text(pendingAction.map { "\(label(for: $0.action)) · \($0.targetLabel)" } ?? localizer.t.actionConfirmMessage)
        }
        .alert(
            localizer.t.error,
            isPresented: Binding(
                get: { actionErrorMessage != nil },
                set: { if !$0 { actionErrorMessage = nil } }
            )
        ) {
            Button(localizer.t.confirm, role: .cancel) { actionErrorMessage = nil }
        } message: {
            Text(actionErrorMessage ?? localizer.t.error)
        }
    }

    // MARK: - Instance picker

    private var instancePicker: some View {
        let instances = servicesStore.instances(for: .unraid)
        return Group {
            if instances.count > 1 {
                VStack(alignment: .leading, spacing: 12) {
                    Text(localizer.t.dashboardInstances)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.textMuted)
                        .textCase(.uppercase)

                    ForEach(instances) { instance in
                        Button {
                            HapticManager.light()
                            selectedInstanceId = instance.id
                            servicesStore.setPreferredInstance(id: instance.id, for: .unraid)
                            overview = nil
                        } label: {
                            HStack(spacing: 10) {
                                Circle()
                                    .fill(instance.id == selectedInstanceId ? accentColor : AppTheme.textMuted.opacity(0.4))
                                    .frame(width: 10, height: 10)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(instance.displayLabel)
                                        .font(.subheadline.weight(.semibold))
                                    Text(instance.url)
                                        .font(.caption)
                                        .foregroundStyle(AppTheme.textMuted)
                                        .lineLimit(1)
                                }
                                Spacer()
                            }
                            .padding(14)
                            .glassCard(tint: instance.id == selectedInstanceId ? accentColor.opacity(0.1) : nil)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    // MARK: - System

    private func systemCard(_ info: UnraidInfo) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "server.rack").foregroundStyle(accentColor)
                Text(info.os?.hostname ?? info.os?.distro ?? localizer.t.unraidSectionSystem)
                    .font(.headline)
                    .lineLimit(1)
            }

            if let version = info.versions?.unraid, !version.isEmpty {
                detailRow(localizer.t.unraidVersion, version)
            }
            if let kernel = info.versions?.kernel, !kernel.isEmpty {
                detailRow(localizer.t.unraidKernel, kernel)
            }
            if let brand = info.cpu?.brand, !brand.isEmpty {
                let cores = info.cpu?.cores
                let threads = info.cpu?.threads
                let suffix = (cores != nil && threads != nil) ? " (\(cores!)/\(threads!))" : ""
                detailRow(localizer.t.unraidCpu, brand + suffix)
            }
            if let memory = info.memory, let fraction = memory.usedFraction {
                let used = memory.used ?? ((memory.total ?? 0) - (memory.free ?? 0))
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(localizer.t.unraidMemory): \(formatBytes(used)) / \(formatBytes(memory.total))")
                        .font(.caption)
                        .foregroundStyle(AppTheme.textMuted)
                    ProgressView(value: fraction).tint(accentColor)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassCard(tint: accentColor.opacity(0.08))
    }

    // MARK: - Array

    private func arrayCard(_ array: UnraidArray) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                HStack(spacing: 10) {
                    Image(systemName: "externaldrive.fill").foregroundStyle(accentColor)
                    Text(localizer.t.unraidSectionArray).font(.headline)
                }
                Spacer()
                stateBadge(array.state ?? localizer.t.unraidStateUnknown, positive: array.isStarted)
            }

            if let capacity = array.capacity?.kilobytes, let fraction = capacity.usedFraction {
                VStack(alignment: .leading, spacing: 6) {
                    // The Unraid API reports array capacity in kilobytes.
                    Text("\(formatBytes(capacity.used.map { $0 * 1024 })) / \(formatBytes(capacity.total.map { $0 * 1024 }))")
                        .font(.caption)
                        .foregroundStyle(AppTheme.textMuted)
                    ProgressView(value: fraction).tint(accentColor)
                }
            }

            actionButtons(
                array.isStarted
                    ? [.arrayStop, .parityCheckStart, .parityCheckCancel]
                    : [.arrayStart],
                targetId: nil,
                targetLabel: localizer.t.unraidSectionArray,
                busyKey: nil
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassCard()
    }

    @ViewBuilder
    private func diskSection(_ title: String, disks: [UnraidDisk]) -> some View {
        if !disks.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader(title)
                ForEach(Array(disks.enumerated()), id: \.offset) { _, disk in
                    diskRow(disk)
                }
            }
        }
    }

    private func diskRow(_ disk: UnraidDisk) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(disk.displayName).font(.subheadline.weight(.semibold)).lineLimit(1)
                    let details = [
                        disk.device,
                        disk.temp.map { "\($0) °C" },
                        disk.numErrors.flatMap { $0 > 0 ? String(format: localizer.t.unraidDiskErrors, Int($0)) : nil }
                    ].compactMap { $0 }.joined(separator: " · ")
                    if !details.isEmpty {
                        Text(details).font(.caption).foregroundStyle(AppTheme.textMuted).lineLimit(1)
                    }
                }
                Spacer()
                stateBadge(disk.status ?? localizer.t.unraidStateUnknown, positive: disk.isHealthy)
            }
            if let fraction = disk.usedFraction {
                ProgressView(value: fraction).tint(accentColor)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .glassCard()
    }

    // MARK: - Docker

    @ViewBuilder
    private func containerSection(_ overview: UnraidOverview) -> some View {
        if !overview.containers.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader(
                    "\(localizer.t.unraidSectionDocker) · \(overview.runningContainers)/\(overview.containers.count)"
                )
                ForEach(overview.containers) { container in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(container.displayName)
                                    .font(.subheadline.weight(.semibold))
                                    .lineLimit(1)
                                if let image = container.image, !image.isEmpty {
                                    Text(image)
                                        .font(.caption)
                                        .foregroundStyle(AppTheme.textMuted)
                                        .lineLimit(1)
                                }
                            }
                            Spacer()
                            stateBadge(
                                container.state ?? localizer.t.unraidStateUnknown,
                                positive: container.isRunning
                            )
                        }
                        actionButtons(
                            container.isRunning ? [.containerRestart, .containerStop] : [.containerStart],
                            targetId: container.id,
                            targetLabel: container.displayName,
                            busyKey: container.id
                        )
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .glassCard()
                }
            }
        }
    }

    // MARK: - Virtual machines

    @ViewBuilder
    private func vmSection(_ overview: UnraidOverview) -> some View {
        if !overview.vms.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader("\(localizer.t.unraidSectionVms) · \(overview.runningVMs)/\(overview.vms.count)")
                ForEach(overview.vms) { vm in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text(vm.displayName).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Spacer()
                            stateBadge(vm.state ?? localizer.t.unraidStateUnknown, positive: vm.isRunning)
                        }
                        actionButtons(
                            vmActions(for: vm),
                            targetId: vm.uuid,
                            targetLabel: vm.displayName,
                            busyKey: vm.uuid
                        )
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .glassCard()
                }
            }
        }
    }

    private func vmActions(for vm: UnraidVM) -> [UnraidControlledAction] {
        if vm.isPaused { return [.vmResume, .vmStop] }
        if vm.isRunning { return [.vmPause, .vmReboot, .vmStop, .vmForceStop] }
        return [.vmStart]
    }

    // MARK: - Shares and notifications

    @ViewBuilder
    private func shareSection(_ overview: UnraidOverview) -> some View {
        if !overview.shares.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader(localizer.t.unraidSectionShares)
                ForEach(overview.shares) { share in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(share.name ?? "—").font(.subheadline.weight(.semibold)).lineLimit(1)
                            if let comment = share.comment, !comment.isEmpty {
                                Text(comment).font(.caption).foregroundStyle(AppTheme.textMuted).lineLimit(1)
                            }
                        }
                        Spacer()
                        // Share sizes are reported in kilobytes like the array capacity.
                        Text("\(formatBytes(share.used.map { $0 * 1024 })) / \(formatBytes(share.size.map { $0 * 1024 }))")
                            .font(.caption)
                            .foregroundStyle(AppTheme.textMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .glassCard()
                }
            }
        }
    }

    @ViewBuilder
    private func notificationSection(_ overview: UnraidOverview) -> some View {
        if let notifications = overview.notifications,
           !(notifications.list ?? []).isEmpty || (notifications.overview?.unread?.total ?? 0) > 0 {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader(localizer.t.unraidSectionNotifications)
                VStack(alignment: .leading, spacing: 10) {
                    if let unread = notifications.overview?.unread {
                        Text(String(
                            format: localizer.t.unraidNotificationsSummary,
                            unread.total ?? 0,
                            unread.alert ?? 0,
                            unread.warning ?? 0
                        ))
                        .font(.caption)
                        .foregroundStyle(AppTheme.textMuted)
                    }
                    ForEach((notifications.list ?? []).prefix(10)) { notification in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(notification.title ?? notification.subject ?? "—")
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                            if let description = notification.description, !description.isEmpty {
                                Text(description)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.textMuted)
                                    .lineLimit(2)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .glassCard()
            }
        }
    }

    @ViewBuilder
    private func unavailableSectionsCard(_ overview: UnraidOverview) -> some View {
        if !overview.unavailableSections.isEmpty {
            let names = overview.unavailableSections
                .map { sectionName($0) }
                .sorted()
                .joined(separator: ", ")
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle").foregroundStyle(AppTheme.textMuted)
                Text(String(format: localizer.t.unraidSectionsUnavailable, names))
                    .font(.caption)
                    .foregroundStyle(AppTheme.textMuted)
                Spacer()
            }
            .padding(14)
            .glassCard()
        }
    }

    // MARK: - Building blocks

    @ViewBuilder
    private func actionButtons(
        _ actions: [UnraidControlledAction],
        targetId: String?,
        targetLabel: String,
        busyKey: String?
    ) -> some View {
        let isBusy = busyTarget != nil && (busyKey == nil || busyTarget == busyKey)
        LazyVGrid(columns: actionGrid, spacing: 8) {
            ForEach(actions, id: \.rawValue) { action in
                Button {
                    HapticManager.light()
                    if action.requiresConfirmation {
                        pendingAction = PendingUnraidAction(
                            action: action,
                            targetId: targetId,
                            targetLabel: targetLabel
                        )
                    } else {
                        Task { await perform(action, targetId: targetId, confirmed: false) }
                    }
                } label: {
                    Text(label(for: action))
                        .font(.caption.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: 10)
                                .fill(action.isDestructive ? Color.red.opacity(0.12) : accentColor.opacity(0.12))
                        )
                        .foregroundStyle(action.isDestructive ? Color.red : accentColor)
                }
                .buttonStyle(.plain)
                .disabled(isBusy)
                .opacity(isBusy ? 0.5 : 1)
            }
        }
    }

    private func stateBadge(_ text: String, positive: Bool) -> some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(
                Capsule().fill((positive ? accentColor : Color.red).opacity(0.14))
            )
            .foregroundStyle(positive ? accentColor : Color.red)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(AppTheme.textMuted)
            .textCase(.uppercase)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.caption).foregroundStyle(AppTheme.textMuted)
            Spacer()
            Text(value).font(.caption.weight(.medium)).lineLimit(1)
        }
    }

    private func label(for action: UnraidControlledAction) -> String {
        switch action {
        case .containerStart, .vmStart, .arrayStart: return localizer.t.unraidActionStart
        case .containerStop, .vmStop, .arrayStop: return localizer.t.unraidActionStop
        case .containerRestart, .vmReboot: return localizer.t.unraidActionRestart
        case .vmPause: return localizer.t.unraidActionPause
        case .vmResume: return localizer.t.unraidActionResume
        case .vmForceStop: return localizer.t.unraidActionForceStop
        case .parityCheckStart: return localizer.t.unraidActionParityStart
        case .parityCheckCancel: return localizer.t.unraidActionParityCancel
        }
    }

    private func sectionName(_ section: UnraidSection) -> String {
        switch section {
        case .system: return localizer.t.unraidSectionSystem
        case .array: return localizer.t.unraidSectionArray
        case .shares: return localizer.t.unraidSectionShares
        case .docker: return localizer.t.unraidSectionDocker
        case .vms: return localizer.t.unraidSectionVms
        case .notifications: return localizer.t.unraidSectionNotifications
        }
    }

    private func formatBytes(_ value: Int64?) -> String {
        guard let value, value > 0 else { return "—" }
        let units = ["B", "KB", "MB", "GB", "TB", "PB"]
        var remaining = Double(value)
        var unitIndex = 0
        while remaining >= 1024, unitIndex < units.count - 1 {
            remaining /= 1024
            unitIndex += 1
        }
        return String(format: "%.1f %@", remaining, units[unitIndex])
    }

    // MARK: - Data

    private func fetchDashboard() async {
        do {
            if overview == nil {
                state = .loading
            }
            guard let client = await servicesStore.unraidClient(instanceId: selectedInstanceId) else {
                state = .error(.notConfigured)
                return
            }
            let fetched = try await client.overview()
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                overview = fetched
                state = .loaded(())
            }
        } catch let apiError as APIError {
            if overview == nil { state = .error(apiError) }
        } catch {
            if overview == nil { state = .error(.custom(error.localizedDescription)) }
        }
    }

    private func perform(_ action: UnraidControlledAction, targetId: String?, confirmed: Bool) async {
        guard busyTarget == nil else { return }
        let busyKey = targetId ?? action.rawValue
        busyTarget = busyKey
        actionErrorMessage = nil
        defer { busyTarget = nil }

        do {
            guard let client = await servicesStore.unraidClient(instanceId: selectedInstanceId) else {
                throw APIError.notConfigured
            }
            let audit = await servicesStore.controlledActionCoordinator.execute(
                request: action.request(
                    instanceId: selectedInstanceId,
                    targetId: targetId,
                    confirmed: confirmed
                ),
                actorRole: .admin,
                providerCapabilities: ProviderRegistry.descriptor(for: .unraid).capabilities
            ) {
                do {
                    try await client.perform(action, targetId: targetId)
                } catch is CancellationError {
                    throw CancellationError()
                } catch let error as ControlledActionOperationError {
                    throw error
                } catch {
                    throw ControlledActionOperationError(
                        reasonCode: "unraid-outcome-indeterminate",
                        disposition: .nonRetryable
                    )
                }
            }
            guard audit.state == .succeeded else {
                throw APIError.custom(audit.reasonCode)
            }
            HapticManager.success()
            // Unraid applies container and VM state changes asynchronously; give it a moment
            // before reloading so the card settles on the new state.
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            await fetchDashboard()
        } catch {
            HapticManager.error()
            actionErrorMessage = error.localizedDescription
        }
    }
}
