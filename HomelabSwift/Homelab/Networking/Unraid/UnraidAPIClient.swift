import Foundation

// MARK: - GraphQL envelope

struct UnraidGraphQLRequest: Encodable {
    let query: String
}

struct UnraidGraphQLError: Decodable {
    let message: String
}

struct UnraidGraphQLResponse<T: Decodable>: Decodable {
    let data: T?
    let errors: [UnraidGraphQLError]?
}

// MARK: - Models

struct UnraidInfoData: Decodable {
    let info: UnraidInfo?
}

struct UnraidInfo: Decodable, Equatable {
    let os: UnraidOS?
    let cpu: UnraidCPU?
    let memory: UnraidMemory?
    let versions: UnraidVersions?
}

struct UnraidOS: Decodable, Equatable {
    let platform: String?
    let distro: String?
    let release: String?
    let hostname: String?
    let uptime: String?
}

struct UnraidCPU: Decodable, Equatable {
    let manufacturer: String?
    let brand: String?
    let cores: Int?
    let threads: Int?
}

struct UnraidMemory: Decodable, Equatable {
    let total: Int64?
    let free: Int64?
    let used: Int64?

    var usedFraction: Double? {
        guard let total, total > 0 else { return nil }
        let usedBytes = used ?? (free.map { total - $0 })
        guard let usedBytes else { return nil }
        return min(max(Double(usedBytes) / Double(total), 0), 1)
    }
}

struct UnraidVersions: Decodable, Equatable {
    let unraid: String?
    let kernel: String?
}

struct UnraidArrayData: Decodable {
    let array: UnraidArray?
}

struct UnraidArray: Decodable, Equatable {
    let state: String?
    let capacity: UnraidArrayCapacity?
    let parities: [UnraidDisk]?
    let disks: [UnraidDisk]?
    let caches: [UnraidDisk]?

    /// True while the array is started, the only state in which shares and user data are online.
    var isStarted: Bool { state?.caseInsensitiveCompare("STARTED") == .orderedSame }
}

struct UnraidArrayCapacity: Decodable, Equatable {
    let kilobytes: UnraidCapacityValues?
    let disks: UnraidCapacityValues?
}

struct UnraidCapacityValues: Decodable, Equatable {
    let free: Int64?
    let used: Int64?
    let total: Int64?

    var usedFraction: Double? {
        guard let total, total > 0, let used else { return nil }
        return min(max(Double(used) / Double(total), 0), 1)
    }
}

struct UnraidDisk: Decodable, Equatable, Identifiable {
    let id: String?
    let idx: Int?
    let name: String?
    let device: String?
    let size: Int64?
    let status: String?
    let temp: Int?
    let numErrors: Int64?
    let fsSize: Int64?
    let fsFree: Int64?
    let fsUsed: Int64?
    let type: String?

    var displayName: String {
        if let name, !name.isEmpty { return name }
        if let device, !device.isEmpty { return device }
        return id ?? "—"
    }

    var isHealthy: Bool {
        guard let status else { return true }
        return status.caseInsensitiveCompare("DISK_OK") == .orderedSame
    }

    var usedFraction: Double? {
        guard let fsSize, fsSize > 0 else { return nil }
        let usedValue = fsUsed ?? fsFree.map { fsSize - $0 }
        guard let usedValue else { return nil }
        return min(max(Double(usedValue) / Double(fsSize), 0), 1)
    }
}

struct UnraidSharesData: Decodable {
    let shares: [UnraidShare]?
}

struct UnraidShare: Decodable, Equatable, Identifiable {
    let name: String?
    let comment: String?
    let free: Int64?
    let used: Int64?
    let size: Int64?

    var id: String { name ?? UUID().uuidString }
}

struct UnraidDockerData: Decodable {
    let docker: UnraidDocker?
}

struct UnraidDocker: Decodable {
    let containers: [UnraidContainer]?
}

struct UnraidContainer: Decodable, Equatable, Identifiable {
    let id: String
    let names: [String]?
    let image: String?
    let state: String?
    let status: String?
    let autoStart: Bool?

    var displayName: String {
        guard let raw = names?.first, !raw.isEmpty else { return id }
        return raw.hasPrefix("/") ? String(raw.dropFirst()) : raw
    }

    var isRunning: Bool { state?.caseInsensitiveCompare("RUNNING") == .orderedSame }
}

struct UnraidVMsData: Decodable {
    let vms: UnraidVMs?
}

struct UnraidVMs: Decodable {
    let domain: [UnraidVM]?
}

struct UnraidVM: Decodable, Equatable, Identifiable {
    let uuid: String
    let name: String?
    let state: String?

    var id: String { uuid }
    var displayName: String { (name?.isEmpty == false ? name : nil) ?? uuid }
    var isRunning: Bool { state?.caseInsensitiveCompare("RUNNING") == .orderedSame }
    var isPaused: Bool {
        state?.caseInsensitiveCompare("PAUSED") == .orderedSame ||
            state?.caseInsensitiveCompare("PMSUSPENDED") == .orderedSame
    }
}

struct UnraidNotificationsData: Decodable {
    let notifications: UnraidNotifications?
}

struct UnraidNotifications: Decodable, Equatable {
    let overview: UnraidNotificationOverview?
    let list: [UnraidNotification]?
}

struct UnraidNotificationOverview: Decodable, Equatable {
    let unread: UnraidNotificationCounts?
}

struct UnraidNotificationCounts: Decodable, Equatable {
    let info: Int?
    let warning: Int?
    let alert: Int?
    let total: Int?
}

struct UnraidNotification: Decodable, Equatable, Identifiable {
    let id: String
    let title: String?
    let subject: String?
    let description: String?
    let importance: String?
    let timestamp: String?
}

/// Sections are fetched independently, so an endpoint a given Unraid build does not expose
/// degrades that card only instead of failing the whole dashboard.
enum UnraidSection: String, CaseIterable, Equatable, Sendable {
    case system, array, shares, docker, vms, notifications
}

struct UnraidOverview: Equatable, Sendable {
    var info: UnraidInfo?
    var array: UnraidArray?
    var shares: [UnraidShare] = []
    var containers: [UnraidContainer] = []
    var vms: [UnraidVM] = []
    var notifications: UnraidNotifications?
    var unavailableSections: Set<UnraidSection> = []

    var runningContainers: Int { containers.filter(\.isRunning).count }
    var runningVMs: Int { vms.filter(\.isRunning).count }
}

// MARK: - Actions

enum UnraidControlledAction: String, CaseIterable, Equatable, Sendable {
    case containerStart = "docker.container.start"
    case containerStop = "docker.container.stop"
    case containerRestart = "docker.container.restart"
    case vmStart = "vm.start"
    case vmStop = "vm.stop"
    case vmPause = "vm.pause"
    case vmResume = "vm.resume"
    case vmForceStop = "vm.force-stop"
    case vmReboot = "vm.reboot"
    case arrayStart = "array.start"
    case arrayStop = "array.stop"
    case parityCheckStart = "parity.check.start"
    case parityCheckCancel = "parity.check.cancel"

    var risk: ControlledActionRisk {
        switch self {
        case .containerStart, .vmStart, .vmResume: return .low
        case .containerStop, .containerRestart, .vmStop, .vmPause, .vmReboot,
             .parityCheckStart, .parityCheckCancel: return .medium
        case .vmForceStop, .arrayStart: return .high
        case .arrayStop: return .critical
        }
    }

    var requiresConfirmation: Bool { risk != .low }

    var isDestructive: Bool {
        switch self {
        case .containerStop, .vmStop, .vmForceStop, .arrayStop, .parityCheckCancel: return true
        default: return false
        }
    }

    func targetRef(_ targetId: String?) -> String {
        switch self {
        case .containerStart, .containerStop, .containerRestart:
            return "container/\((targetId ?? "").lowercased())"
        case .vmStart, .vmStop, .vmPause, .vmResume, .vmForceStop, .vmReboot:
            return "vm/\((targetId ?? "").lowercased())"
        case .arrayStart, .arrayStop:
            return "array"
        case .parityCheckStart, .parityCheckCancel:
            return "parity-check"
        }
    }

    func request(
        instanceId: UUID,
        targetId: String?,
        confirmed: Bool,
        requestId: UUID = UUID(),
        requestedAt: Date = Date(),
        idempotencyKey: UUID = UUID()
    ) -> ControlledActionRequest {
        ControlledActionRequest(
            id: requestId.uuidString,
            providerRef: "unraid:\(instanceId.uuidString.lowercased())",
            action: rawValue,
            targetRef: targetRef(targetId),
            risk: risk,
            requestedAt: ISO8601DateFormatter().string(from: requestedAt),
            idempotencyKey: idempotencyKey.uuidString,
            confirmed: confirmed
        )
    }
}

enum UnraidAPIError: LocalizedError {
    case invalidCredentials
    case unsupportedOperation(String?)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .invalidCredentials:
            return "The Unraid API rejected the API key."
        case .unsupportedOperation:
            return "This Unraid build does not support the requested operation."
        case .server(let message):
            return message
        }
    }
}

// MARK: - API Client

/// Talks to the official Unraid API (`unraid-api`, Unraid 7.x): a single GraphQL endpoint at
/// `<server>/graphql`, authenticated with an API key in the `x-api-key` header.
///
/// The schema was reorganised between Unraid releases, so every operation is a list of candidate
/// documents tried in order: a schema error falls through to the next candidate, any other error
/// is reported unchanged. Ids are inlined as quoted literals so a document never has to name a
/// scalar type (`ID` vs `String` vs `PrefixedID`) that differs between releases.
actor UnraidAPIClient {
    private let instanceId: UUID
    private var engine: BaseNetworkEngine
    private var storedAllowSelfSigned = true
    private var baseURL: String = ""
    private var fallbackURL: String = ""
    private var apiKey: String = ""

    init(instanceId: UUID) {
        self.instanceId = instanceId
        self.engine = BaseNetworkEngine(serviceType: .unraid, instanceId: instanceId)
    }

    // MARK: Configuration

    func configure(url: String, apiKey: String, fallbackUrl: String? = nil, allowSelfSigned: Bool? = nil) {
        self.baseURL = Self.cleanURL(url)
        self.fallbackURL = Self.cleanURL(fallbackUrl ?? "")
        self.apiKey = apiKey
        if let allowSelfSigned {
            storedAllowSelfSigned = allowSelfSigned
        }
        engine = BaseNetworkEngine(
            serviceType: .unraid,
            instanceId: self.instanceId,
            allowSelfSigned: self.storedAllowSelfSigned
        )
    }

    private func headers() -> [String: String] {
        [
            "x-api-key": apiKey,
            "Accept": "application/json",
            "Content-Type": "application/json"
        ]
    }

    // MARK: Ping and login

    func ping() async -> Bool {
        guard !baseURL.isEmpty else { return false }
        return (try? await section(UnraidGraphQL.ping, as: UnraidInfoData.self)) != nil
    }

    func authenticate(url: String, apiKey: String, fallbackUrl: String? = nil) async throws {
        let cleanBase = Self.cleanURL(url)
        let cleanFallback = Self.cleanURL(fallbackUrl ?? "")
        let requestHeaders = [
            "x-api-key": apiKey,
            "Accept": "application/json",
            "Content-Type": "application/json"
        ]

        var schemaMismatch: String?
        for document in UnraidGraphQL.ping {
            let body = try JSONEncoder().encode(UnraidGraphQLRequest(query: document))
            let response: UnraidGraphQLResponse<UnraidInfoData> = try await engine.request(
                baseURL: cleanBase,
                fallbackURL: cleanFallback,
                path: "/graphql",
                method: "POST",
                headers: requestHeaders,
                body: body
            )
            if let failure = response.errors?.first?.message {
                if UnraidGraphQL.isSchemaMismatch(failure) {
                    schemaMismatch = failure
                    continue
                }
                throw Self.classify(failure)
            }
            return
        }
        throw UnraidAPIError.unsupportedOperation(schemaMismatch)
    }

    // MARK: Reads

    func overview() async throws -> UnraidOverview {
        async let systemTask = optionalSection(UnraidGraphQL.system, as: UnraidInfoData.self)
        async let arrayTask = optionalSection(UnraidGraphQL.array, as: UnraidArrayData.self)
        async let sharesTask = optionalSection(UnraidGraphQL.shares, as: UnraidSharesData.self)
        async let dockerTask = optionalSection(UnraidGraphQL.docker, as: UnraidDockerData.self)
        async let vmsTask = optionalSection(UnraidGraphQL.vms, as: UnraidVMsData.self)
        async let notificationsTask = optionalSection(
            UnraidGraphQL.notifications,
            as: UnraidNotificationsData.self
        )

        let system = try await systemTask
        let array = try await arrayTask
        let shares = try await sharesTask
        let docker = try await dockerTask
        let vms = try await vmsTask
        let notifications = try await notificationsTask

        if system == nil, array == nil, docker == nil {
            throw UnraidAPIError.unsupportedOperation(nil)
        }

        var unavailable: Set<UnraidSection> = []
        if system == nil { unavailable.insert(.system) }
        if array == nil { unavailable.insert(.array) }
        if shares == nil { unavailable.insert(.shares) }
        if docker == nil { unavailable.insert(.docker) }
        if vms == nil { unavailable.insert(.vms) }
        if notifications == nil { unavailable.insert(.notifications) }

        return UnraidOverview(
            info: system?.info,
            array: array?.array,
            shares: shares?.shares ?? [],
            containers: docker?.docker?.containers ?? [],
            vms: vms?.vms?.domain ?? [],
            notifications: notifications?.notifications,
            unavailableSections: unavailable
        )
    }

    func containers() async throws -> [UnraidContainer] {
        let data = try await optionalSection(UnraidGraphQL.docker, as: UnraidDockerData.self)
        return data?.docker?.containers ?? []
    }

    // MARK: Mutations

    func perform(_ action: UnraidControlledAction, targetId: String?) async throws {
        switch action {
        case .containerStart:
            try await mutate(UnraidGraphQL.startContainer(try Self.requireTarget(targetId)))
        case .containerStop:
            try await mutate(UnraidGraphQL.stopContainer(try Self.requireTarget(targetId)))
        case .containerRestart:
            let target = try Self.requireTarget(targetId)
            do {
                try await mutate(UnraidGraphQL.restartContainer(target))
            } catch let error as UnraidAPIError {
                guard case .unsupportedOperation = error else { throw error }
                // Builds without a restart mutation get the stop-then-start sequence the
                // Unraid web UI performs for the same button.
                try await mutate(UnraidGraphQL.stopContainer(target))
                try await mutate(UnraidGraphQL.startContainer(target))
            }
        case .vmStart:
            try await mutate(UnraidGraphQL.startVM(try Self.requireTarget(targetId)))
        case .vmStop:
            try await mutate(UnraidGraphQL.stopVM(try Self.requireTarget(targetId)))
        case .vmPause:
            try await mutate(UnraidGraphQL.pauseVM(try Self.requireTarget(targetId)))
        case .vmResume:
            try await mutate(UnraidGraphQL.resumeVM(try Self.requireTarget(targetId)))
        case .vmForceStop:
            try await mutate(UnraidGraphQL.forceStopVM(try Self.requireTarget(targetId)))
        case .vmReboot:
            try await mutate(UnraidGraphQL.rebootVM(try Self.requireTarget(targetId)))
        case .arrayStart:
            try await mutate(UnraidGraphQL.startArray)
        case .arrayStop:
            try await mutate(UnraidGraphQL.stopArray)
        case .parityCheckStart:
            try await mutate(UnraidGraphQL.startParityCheck(correcting: true))
        case .parityCheckCancel:
            try await mutate(UnraidGraphQL.cancelParityCheck)
        }
    }

    // MARK: GraphQL plumbing

    private func mutate(_ documents: [String]) async throws {
        _ = try await section(documents, as: UnraidMutationAck.self)
    }

    /// Returns nil when no candidate document is understood by this Unraid build.
    private func optionalSection<T: Decodable>(_ documents: [String], as type: T.Type) async throws -> T? {
        do {
            return try await section(documents, as: type)
        } catch let error as UnraidAPIError {
            guard case .unsupportedOperation = error else { throw error }
            return nil
        }
    }

    private func section<T: Decodable>(_ documents: [String], as type: T.Type) async throws -> T {
        guard !baseURL.isEmpty else { throw APIError.notConfigured }

        var schemaMismatch: String?
        for document in documents {
            let body = try JSONEncoder().encode(UnraidGraphQLRequest(query: document))
            let response: UnraidGraphQLResponse<T> = try await engine.request(
                baseURL: baseURL,
                fallbackURL: fallbackURL,
                path: "/graphql",
                method: "POST",
                headers: headers(),
                body: body
            )
            if let failure = response.errors?.first?.message {
                if UnraidGraphQL.isSchemaMismatch(failure) {
                    schemaMismatch = failure
                    continue
                }
                throw Self.classify(failure)
            }
            guard let payload = response.data else {
                throw UnraidAPIError.server("The Unraid API returned an empty response.")
            }
            return payload
        }
        throw UnraidAPIError.unsupportedOperation(schemaMismatch)
    }

    private static func classify(_ message: String) -> UnraidAPIError {
        let normalized = message.lowercased()
        let unauthorized = ["unauthorized", "unauthenticated", "forbidden", "api key", "permission"]
        if unauthorized.contains(where: normalized.contains) {
            return .invalidCredentials
        }
        return .server(message)
    }

    private static func requireTarget(_ targetId: String?) throws -> String {
        guard let targetId, !targetId.isEmpty else { throw APIError.notConfigured }
        return targetId
    }

    private static func cleanURL(_ url: String) -> String {
        var value = url.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value = String(value.dropLast()) }
        return value
    }
}

/// Mutations return payloads that differ per Unraid release; only the absence of an error matters.
struct UnraidMutationAck: Decodable {
    init(from decoder: Decoder) throws {}
}

// MARK: - GraphQL documents

enum UnraidGraphQL {

    static let system: [String] = [
        """
        query {
          info {
            os { platform distro release hostname uptime }
            cpu { manufacturer brand cores threads }
            memory { total free used }
            versions { unraid kernel }
          }
        }
        """,
        """
        query {
          info {
            os { platform distro release uptime }
            versions { unraid }
          }
        }
        """
    ]

    static let array: [String] = [
        """
        query {
          array {
            state
            capacity { kilobytes { free used total } disks { free used total } }
            parities { id idx name device size status temp numErrors type }
            disks { id idx name device size status temp numErrors fsSize fsFree fsUsed type }
            caches { id idx name device size status temp numErrors fsSize fsFree fsUsed type }
          }
        }
        """,
        """
        query {
          array {
            state
            capacity { kilobytes { free used total } }
            disks { id name device size status temp type }
          }
        }
        """
    ]

    static let shares: [String] = [
        "query { shares { name comment free used size } }",
        "query { shares { name free used } }"
    ]

    static let docker: [String] = [
        "query { docker { containers { id names image state status autoStart } } }",
        "query { docker { containers { id names image state status } } }"
    ]

    static let vms: [String] = [
        "query { vms { domain { uuid name state } } }"
    ]

    static let notifications: [String] = [
        """
        query {
          notifications {
            overview { unread { info warning alert total } }
            list(filter: { type: UNREAD, offset: 0, limit: 30 }) {
              id title subject description importance timestamp
            }
          }
        }
        """,
        "query { notifications { overview { unread { info warning alert total } } } }"
    ]

    /// Cheapest document that still proves both connectivity and a valid API key.
    static let ping: [String] = [
        "query { info { os { platform } } }"
    ]

    static func startContainer(_ id: String) -> [String] {
        [
            "mutation { docker { start(id: \(quote(id))) { id state } } }",
            "mutation { startContainer(id: \(quote(id))) { id state } }"
        ]
    }

    static func stopContainer(_ id: String) -> [String] {
        [
            "mutation { docker { stop(id: \(quote(id))) { id state } } }",
            "mutation { stopContainer(id: \(quote(id))) { id state } }"
        ]
    }

    static func restartContainer(_ id: String) -> [String] {
        ["mutation { docker { restart(id: \(quote(id))) { id state } } }"]
    }

    static func startVM(_ id: String) -> [String] {
        [
            "mutation { vm { start(id: \(quote(id))) } }",
            "mutation { startVm(id: \(quote(id))) }"
        ]
    }

    static func stopVM(_ id: String) -> [String] {
        [
            "mutation { vm { stop(id: \(quote(id))) } }",
            "mutation { stopVm(id: \(quote(id))) }"
        ]
    }

    static func pauseVM(_ id: String) -> [String] {
        [
            "mutation { vm { pause(id: \(quote(id))) } }",
            "mutation { pauseVm(id: \(quote(id))) }"
        ]
    }

    static func resumeVM(_ id: String) -> [String] {
        [
            "mutation { vm { resume(id: \(quote(id))) } }",
            "mutation { resumeVm(id: \(quote(id))) }"
        ]
    }

    static func forceStopVM(_ id: String) -> [String] {
        [
            "mutation { vm { forceStop(id: \(quote(id))) } }",
            "mutation { forceStopVm(id: \(quote(id))) }"
        ]
    }

    static func rebootVM(_ id: String) -> [String] {
        [
            "mutation { vm { reboot(id: \(quote(id))) } }",
            "mutation { rebootVm(id: \(quote(id))) }"
        ]
    }

    static let startArray: [String] = [
        "mutation { array { setState(input: { desiredState: START }) { state } } }",
        "mutation { startArray { state } }"
    ]

    static let stopArray: [String] = [
        "mutation { array { setState(input: { desiredState: STOP }) { state } } }",
        "mutation { stopArray { state } }"
    ]

    static func startParityCheck(correcting: Bool) -> [String] {
        [
            "mutation { parityCheck { start(correct: \(correcting)) } }",
            "mutation { startParityCheck(correct: \(correcting)) }"
        ]
    }

    static let cancelParityCheck: [String] = [
        "mutation { parityCheck { cancel } }",
        "mutation { cancelParityCheck }"
    ]

    /// Marks a GraphQL error as "this build does not know that field", which is the signal to
    /// fall through to the next candidate document rather than surface an error.
    static func isSchemaMismatch(_ message: String) -> Bool {
        let normalized = message.lowercased()
        return schemaMismatchMarkers.contains { normalized.contains($0) }
    }

    private static let schemaMismatchMarkers = [
        "cannot query field",
        "unknown field",
        "unknown argument",
        "unknown type",
        "is not defined by type",
        "did you mean",
        "no field named",
        "must not have a selection since type"
    ]

    private static func quote(_ raw: String) -> String {
        let escaped = raw
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
            .replacingOccurrences(of: "\t", with: "\\t")
        return "\"\(escaped)\""
    }
}
