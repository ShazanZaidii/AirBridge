// The one type DropLogic.swift mentions from elsewhere in the app (only the fields it reads), enough for the self-check to compile on its own.
struct TransferStatus {
    enum Direction { case incoming, outgoing }
    var fileCount: Int; var firstName: String
    var direction: Direction = .incoming; var total: Int64 = 0; var elapsed: Double = 0
}
