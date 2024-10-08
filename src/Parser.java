

import java.io.*;
import java.util.*;

/** Exception class for representing parsing errors. */
class ParseException extends RuntimeException {
  ParseException(String s) { super(s); }
}

/** A parser class for Jam.  Each program requires a separate parser object. */
class Parser {
  
  private Lexer in;
  
  Parser(Lexer i) {
    in = i;
  }
  
  Parser(Reader inputStream) { this(new Lexer(inputStream)); }
  
  Parser(String fileName) throws IOException { this(new FileReader(fileName)); }
  
  Lexer lexer() { return in; }

  /** Parses a Jam program which is simply an expression (Exp) */
  public AST parse() throws ParseException {
    // Parse the main expression and obtain its AST
    AST progAST = parseExp();

    // Read the next token to check for end of file or extra tokens
    Token nextToken = in.readToken();

    // If there is no more data (EOF), return the parsed AST
    if (nextToken == null) {
      return progAST;
    }
    else {
      // If there is an extra token, throw an error indicating unexpected data
      throw new ParseException("Unexpected data");
    }
  }
  
  /** Parses:
    *   <exp> :: = if <exp> then <exp> else <exp>
    *            | let <prop-def-list> in <exp>
    *            | map <id-list> to <exp>
    *            | <term> { <biop> <term> }*  // (left associatively!)
    */
  private AST parseExp() {
    // Read the next token from the input to determine the type of expression to parse
    Token token = in.readToken();

    // Directly return the appropriate AST based on the token type
    if (token == Lexer.IF) {
      return parseIf();
    }
    if (token == Lexer.LET) {
      return parseLet();
    }
    if (token == Lexer.MAP) {
      return parseMap();
    }

    // If the token does not match any special structures, parse a term
    AST exp = parseTerm(token);

    // Handle possible chaging of binary operations
    Token nextToken = in.peek();
    while (nextToken instanceof OpToken) {
      OpToken op = (OpToken) nextToken;
      in.readToken(); // Advance to the next token after recognizing an operator
      if (!op.isBinOp()) {
          error(nextToken, "binary operator");
      }
      AST newTerm = parseTerm(in.readToken());
      exp = new BinOpApp(op.toBinOp(), exp, newTerm);
      nextToken = in.peek();
    }
    return exp;
  }

  private AST parseTerm(Token token) {
    // Check for unary opeartions
    if (token instanceof OpToken) {
      OpToken opToken = (OpToken) token;
      // Verify if the operator is unary, throw an error otherwise
      if (!opToken.isUnOp()) {
        error(opToken, "unary operator");
      }
      // Parse the term following the unary operator recursively
      return new UnOpApp(opToken.toUnOp(), parseTerm(in.readToken()));
    }

    // Directly return the token if it is a constant
    if (token instanceof Constant) {
      return (Constant) token;
    }

    // Parse the current token as a facttor
    AST factorAST = parseFactor(token);

    // Look ahead to see if there's a function application
    Token lookaheadToken = in.peek();
    if (lookaheadToken == LeftParen.ONLY) {
      in.readToken(); // Consume the opening parenthesis
      AST[] arguments = parseArgs(); // Parse function arguments, including the closing parenthesis
      // Create an application AST node with the parsed factgor and arguments
      return new App(factorAST, arguments);
    }
    
    // If there's no function application, return the factor AST node
    return factorAST;
  }

  private AST parseFactor(Token token) {
    // Handle parsing when the token represents an expression enclosed in parentheses
    if (token == LeftParen.ONLY) {
      AST exp = parseExp(); // Parse the expression inside the parentheses
      token = in.readToken(); // Read the next token, expecting a closing parenthese
      if (token != RightParen.ONLY) {
        error(token, "')'");
      }
      return exp;
    }
  
    // Ensure the token is either a Primitive Function or a Variable
    if (!(token instanceof PrimFun) && !(token instanceof Variable)) {
      error(token, "constant, primitive, variable, or `('"); // Error if the token is none of these
    }

    // If the token is a Primitive Function or a Variable, cast and return it as a Term
    return (Term) token;
  }

  private AST parseIf() {
    // Parse the condition expression of the if statement
    AST condition = parseExp();

    // Expect the 'then' keyword following the condition expression
    Token thenToken = in.readToken();
    if (thenToken != Lexer.THEN) {
      error(thenToken, "'then'"); // Report an error if 'then' keyword is missing
    }

    // Parse the consequent expression to be executed if the condition is true
    AST consequent = parseExp();

    // Expect the 'else' keyword following the consequent expression
    Token elseToken = in.readToken();
    if (elseToken != Lexer.ELSE) {
      error(elseToken, "'else'"); // Report an error if 'else' keyword is missing
    }

    // Parse the alternative expression to be executed if the condition is false
    AST alternative = parseExp();

    // Construct and return the If AST node with the parsed components
    return new If(condition, consequent, alternative);
  }

  private AST parseLet() {
    // Parse definitions in the 'let' expression. The 'false' parameter indicates that the right-hand side of the definitions doesn't need to be a 'Map'.
    Def[] definitions = parseDefs(false);

    // Parse the body of the 'let' expression, which is evaluated with the defined variable in scope.
    AST body = parseExp();

    // Create a new 'Let' AST node using the parsed definitions and body then return this node
    return new Let(definitions, body);
  }


  private AST parseMap() {
    // Parse the list of variables to be mapped
    Variable[] variables = parseVars(); // Consumes the delimiter 'to'

    // Parse the expression that defines what each variable in the list maps to
    AST body = parseExp();

    // Construct and return a 'Map' AST node with the parsed variables and the corresponding body expression
    return new Map(variables, body);
  }

  private AST[] parseExps(Token separator, Token delimiter) {
    // Initialize a list to store the parsed expressions
    LinkedList<AST> expressions = new LinkedList<AST>();
    Token nextToken = in.peek(); // Look at the next token without consuming it

    // If the next token is the delimiter, consume it and return an empty array
    if (nextToken == delimiter) {
      in.readToken(); // Consume the delimiter, typically a closing parenthesis
      return new AST[0];
    }

    // Parse expressions seperated by the specified seperator until the delimiter is reached
    do {
      AST exp = parseExp(); // Parse the next expression
      expressions.addLast(exp); // Add the parsed expression to the list
      nextToken = in.readToken(); // Move to the next token, which could be a seperator
    } while (nextToken == separator); // Continue as long as the seperator is encountered

    // Check if the final token is not the expected delimiter
    if (nextToken != delimiter) {
      error(nextToken, "`,' or `)'"); // Throw an error indicating unexpected token
    }

    // Convert the list of expressions to an arrray and return
    return expressions.toArray(new AST[0]);
  }

  private AST[] parseArgs() { return parseExps(Comma.ONLY,RightParen.ONLY); }

  private Variable[] parseVars() {
    // Create a list to hold variables parsed from the input
    LinkedList<Variable> variables = new LinkedList<Variable>();
    Token token = in.readToken(); // Read the first token

    // Check if the first token is 'to', indicating an empty variable list
    if (token == Lexer.TO) {
        return new Variable[0]; // Return an empty array if 'to' is the first token
    }

    // Loop to read variables separated by commas until 'to' is encountered
    do {
        // Ensure the current token is a variable, otherwise throw an error
        if (!(token instanceof Variable)) {
            error(token, "variable");
        }
        variables.addLast((Variable)token); // Add the variable to the list
        
        token = in.readToken(); // Read the next token to check for a comma or 'to'
        if (token == Lexer.TO) {
            break; // Exit the loop if 'to' is found, indicating the end of the list
        }
        if (token != Comma.ONLY) {
            error(token, "'to' or ','"); // Throw an error if neither a comma nor 'to' is found
        }
        // If a comma is found, continue to the next variable
        token = in.readToken();
    } while (true);

    // Convert the list of variables to an array and return it
    return variables.toArray(new Variable[0]);
}

private Def[] parseDefs(boolean forceMap) {
  // Initialize a list to store definition nodes
  LinkedList<Def> definitions = new LinkedList<Def>();
  Token token = in.readToken(); // Read the first token to start parsing definitions
  
  do {
      // Parse a definition from the current token
      Def definition = parseDef(token);
      
      // If forceMap is true, verify that the rhs of the definition is a Map
      if (forceMap && !(definition.rhs() instanceof Map)) {
          throw new ParseException("right hand side of definition `" + definition + "' is not a map expression");
      }
      
      // Add the parsed definition to the list
      definitions.addLast(definition);
      
      // Read the next token to determine if the loop should continue
      token = in.readToken();
  } while (token != Lexer.IN); // Continue until 'in' keyword is encountered
  
  // Convert the list of definitions to an array and return
  return definitions.toArray(new Def[0]);
}

private Def parseDef(Token varToken) {
  // Ensure the initial token is a Variable; if not, throw an error
  if (!(varToken instanceof Variable)) {
      error(varToken, "variable");
  }

  // Read the next token and ensure it is the BIND token (e.g., `:=`)
  Token bindToken = in.readToken();
  if (bindToken != Lexer.BIND) {
      error(bindToken, "`:='"); // Throw an error if the token is not a BIND token
  }

  // Parse the expression that follows the BIND token
  AST expression = parseExp();

  // Read the next token and ensure it is a semi-colon; if not, throw an error
  Token semiColonToken = in.readToken();
  if (semiColonToken != SemiColon.ONLY) {
      error(semiColonToken, "`;'");
  }

  // Create a new definition with the variable and the parsed expression
  return new Def((Variable) varToken, expression);
}

private AST error(Token found, String expected) {
  // Throw a ParseException with a detailed message about what was expected versus what was found
  throw new ParseException("Token `" + found + "' appears where " + expected + " was expected");
}

/**
* A legacy "main" method for running the Parser. This method checks if the command-line
* arguments are valid, creates a parser instance, and parses the given file.
*/
public static void main(String[] args) throws IOException {
  // Check for a legal argument list; if no arguments are provided, return without action
  if (args.length == 0) {
      // Uncomment the following line to provide usage information when no arguments are provided
      // System.out.println("Usage: java Parser <filename>");
      return;
  }

  // Create a new Parser instance using the filename provided as an argument
  Parser parser = new Parser(args[0]);

  // Parse the input file and obtain the AST
  AST program = parser.parse();

}

  
  /* Many more parse methods follow.  I suggest defining separate parse methods for parsing
     Term, If, Map, and Let as shown in the syntax diagrams.  You will need additional
     methods for select contructions.  Let the syntax diagrams and your personal program
     design taste be your guide.  Avoid repeating code patterns. */
}

